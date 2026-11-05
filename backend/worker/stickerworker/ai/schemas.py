"""Strict JSON schemas (Groq `response_format: json_schema, strict: true`).

Strict mode requires every property to be listed in `required` and
`additionalProperties: false` on every object. Length limits are enforced in
code (analysis.normalize_*), not in the schema, to stay within the supported subset.
"""
from __future__ import annotations

from collections.abc import Sequence
from typing import Any

from .. import vocab


def strict_object(properties: dict[str, Any]) -> dict[str, Any]:
    return {"type": "object", "additionalProperties": False, "required": list(properties), "properties": properties}


def _array(items: dict[str, Any]) -> dict[str, Any]:
    return {"type": "array", "items": items}


STRING = {"type": "string"}
BOOLEAN = {"type": "boolean"}


def _enum(values: Sequence[str]) -> dict[str, Any]:
    return {"type": "string", "enum": list(values)}


def analysis_schema(categories: Sequence[str]) -> dict[str, Any]:
    item = strict_object({
        "image": {"type": "integer"},
        "kind": _enum(vocab.KINDS),
        "has_text": BOOLEAN,
        "text": STRING,
        "language": _enum(vocab.LANGUAGES),
        "category": _enum(categories),
        "mood": _array(STRING),
        "tags": _array(STRING),
        "emojis": _array(STRING),
        "description": STRING,
        "nsfw": _enum(vocab.NSFW_LEVELS),
        "minors_likely": BOOLEAN,
        "real_person": BOOLEAN,
        "public_figure": STRING,
        "violence_or_gore": _enum(vocab.VIOLENCE_LEVELS),
        "hate_or_slur": BOOLEAN,
        "sticker_score": {"type": "integer"},
        "best_recipe": _enum(vocab.BEST_RECIPES),
        "crop": strict_object({k: {"type": "number"} for k in ("x", "y", "w", "h")}),
        "caption_ideas": _array(STRING),
    })
    return strict_object({"images": _array(item)})


def caption_schema() -> dict[str, Any]:
    item = strict_object({
        "text": STRING,
        "language": _enum(vocab.CAPTION_LANGUAGES),
        "style": _enum(vocab.CAPTION_STYLES),
        "rating": _enum(vocab.RATINGS),
        "emojis": _array(STRING),
        "trend": STRING,
    })
    return strict_object({"captions": _array(item)})


def pack_name_schema() -> dict[str, Any]:
    return strict_object({"title": STRING, "description": STRING})


JUDGE_REASONS = ("ok", "sexual", "slur", "real_person", "minor", "defamation", "tragedy", "violence", "other")


def judge_schema() -> dict[str, Any]:
    item = strict_object({"index": {"type": "integer"}, "allowed": BOOLEAN, "reason": _enum(JUDGE_REASONS)})
    return strict_object({"verdicts": _array(item)})
