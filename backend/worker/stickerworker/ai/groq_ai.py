"""AI protocol implemented on Groq: vision analysis, captions, caption judge, pack names."""
from __future__ import annotations

import json
from collections.abc import Sequence
from typing import Any

from ..analysis import Analysis, Caption, InvalidAnalysis, normalize_batch, normalize_caption
from ..config import Env
from ..textutil import clean_text, strip_emoji
from . import prompts, schemas
from .base import AIInvalid, CaptionRequest, PackName, PackNameRequest, VisionInput
from .groq import IMAGE_TOKENS, ChatResult, GroqClient, GroqError, SchemaRejected


def _reasoning(value: str) -> dict[str, Any]:
    return {"reasoning_effort": value} if value else {}


def _inline(messages: list[dict[str, Any]], schema: dict[str, Any]) -> list[dict[str, Any]]:
    """json_object fallback: put the schema into the system prompt instead of response_format."""
    first = dict(messages[0])
    first["content"] = f"{first['content']}\nThe JSON must match this JSON schema exactly:\n" \
                       f"{json.dumps(schema, separators=(',', ':'))}"
    return [first, *messages[1:]]


class GroqAI:
    provider = "groq"

    def __init__(self, client: GroqClient, env: Env) -> None:
        self.client = client
        self.env = env
        self._judge_plain = False

    def remaining(self, kind: str) -> int:
        return self.client.budget.remaining(kind)

    def images_per_request(self) -> int:
        """Largest batch whose image tokens + prompt + output fit one minute of TPM."""
        per_image = IMAGE_TOKENS + prompts.PER_IMAGE_OUTPUT
        fit = (self.env.vision_tpm - prompts.PROMPT_OVERHEAD - 150) // per_image
        return max(1, min(self.env.vision_batch, fit))

    def _structured(self, *, purpose: str, model: str, messages: list[dict[str, Any]], schema: dict[str, Any],
                    name: str, kind: str, units: int, max_tokens: int, temperature: float,
                    reasoning: str) -> ChatResult:
        common = dict(purpose=purpose, model=model, kind=kind, units=units, max_tokens=max_tokens,
                      temperature=temperature, extra=_reasoning(reasoning))
        try:
            return self.client.chat_json(messages=messages, schema=schema, schema_name=name, **common)
        except SchemaRejected:
            return self.client.chat_json(messages=_inline(messages, schema), schema=None, **common)

    def analyze(self, items: Sequence[VisionInput], categories: Sequence[str],
                protected: Sequence[str]) -> list[Analysis]:
        result = self._structured(
            purpose="analyze", model=self.env.vision_model, name="sticker_analysis",
            messages=prompts.analyze_messages(items, categories, protected),
            schema=schemas.analysis_schema(categories), kind="vision", units=len(items),
            max_tokens=prompts.vision_max_tokens(len(items)), temperature=0.2, reasoning=self.env.vision_reasoning)
        try:
            return normalize_batch(result.content, categories, len(items))
        except InvalidAnalysis as error:
            raise AIInvalid(f"analysis_invalid:{error}") from None

    def write_captions(self, request: CaptionRequest) -> list[Caption]:
        result = self._structured(
            purpose="caption", model=self.env.text_model, name="sticker_captions",
            messages=prompts.caption_messages(request), schema=schemas.caption_schema(), kind="text", units=1,
            max_tokens=1400, temperature=0.8, reasoning=self.env.text_reasoning)
        raw = result.content.get("captions")
        if not isinstance(raw, list):
            raise AIInvalid("captions_missing")
        return [c for c in (normalize_caption(item) for item in raw[:8]) if c is not None]

    def judge_captions(self, analysis: Analysis, captions: Sequence[Caption]) -> list[bool]:
        if not captions:
            return []
        if not self.env.safety_model:
            return [True] * len(captions)  # judge disabled by configuration
        common = dict(purpose="judge", model=self.env.safety_model, kind="text", units=1, max_tokens=1500,
                      temperature=0.0, messages=prompts.judge_messages(analysis, captions))
        try:
            result = self.client.chat_json(json_mode=not self._judge_plain, **common)
        except GroqError as error:
            if error.status != 400 or self._judge_plain:
                raise
            self._judge_plain = True  # model rejects response_format: ask for plain JSON text
            result = self.client.chat_json(json_mode=False, **common)
        verdicts = result.content.get("verdicts")
        allowed: dict[int, bool] = {}
        if isinstance(verdicts, list):
            for verdict in verdicts:
                if isinstance(verdict, dict) and isinstance(verdict.get("index"), int):
                    allowed[verdict["index"]] = verdict.get("allowed") is True
        return [allowed.get(i, False) for i in range(len(captions))]  # missing verdict = not allowed

    def name_pack(self, request: PackNameRequest) -> PackName:
        result = self._structured(
            purpose="pack_title", model=self.env.small_text_model, name="pack_name",
            messages=prompts.pack_messages(request), schema=schemas.pack_name_schema(), kind="text", units=1,
            max_tokens=600, temperature=0.9, reasoning=self.env.text_reasoning)
        title = clean_text(strip_emoji(clean_text(result.content.get("title"), 80)).strip("\"'“”"), 40)
        description = clean_text(strip_emoji(clean_text(result.content.get("description"), 200)), 120)
        if not title:
            raise AIInvalid("pack_title_missing")
        return PackName(title, description)
