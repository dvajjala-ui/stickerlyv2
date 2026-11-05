"""Deterministic offline stand-in for the AI, used by `run --local` without keys and by tests.

Labels come from hints (file name words, topic, OCR) and the prescreen score, so a
local folder can exercise every path: name a file `baby_...`, `meme_text_...`,
`explicit_...`, `amitabh_bachchan_...`, `cutout_...` and so on. Never used against
Supabase except in --dry-run.
"""
from __future__ import annotations

import hashlib
from collections.abc import Callable, Sequence
from typing import Any

from ..analysis import Analysis, Caption, normalize_analysis
from ..lexicon import lexical_flags
from ..textutil import normalize_name, tokens
from .base import CaptionRequest, PackName, PackNameRequest, VisionInput

MOODS = ("happy", "angry", "shocked", "sarcastic", "sad", "excited")
HINGLISH_MOOD = {"happy": "khush", "angry": "gussa", "shocked": "hairan", "sarcastic": "taana", "sad": "udaas",
                 "excited": "josh"}
MOOD_EMOJI = {"happy": "😄", "angry": "😠", "shocked": "😱", "sarcastic": "😏", "sad": "😢", "excited": "🤩"}
GENERAL_LINES = ("Kya scene hai bhai", "Mood kharab mat karo", "Aaj toh party banti hai", "Main toh chala",
                 "Bas kar pagle rulayega kya", "Ye kya ho raha hai", "Chill maar yaar", "Sach mein?",
                 "Arre waah kya baat hai", "Kuch bhi bolta hai", "Monday phir aa gaya", "Chai pe charcha")
ADULT_LINES = ("Shaadi ke baad samjhoge", "Itna bhi mat dekho", "Non veg joke mat sunao", "Raat ko baat karte hain")
SAVAGE_LINES = ("Tu rehne de bhai", "Aukaat mein reh", "Kya bakwas hai")
HINDI_LINES = ("हद है यार", "क्या बात है")
_STOP = {"jpg", "jpeg", "png", "gif", "webp", "img", "image", "photo", "copy", "final", "the", "and"}
FULL_CROP = {"x": 0.0, "y": 0.0, "w": 1.0, "h": 1.0}  # pictures with text keep everything
SUBJECT_CROP = {"x": 0.12, "y": 0.1, "w": 0.76, "h": 0.8}


def _digest(*parts: str) -> int:
    return int(hashlib.sha256("|".join(parts).encode()).hexdigest()[:12], 16)


class FakeAI:
    provider = "fake"

    def __init__(self, ledger: Callable[[dict[str, Any]], None] | None = None) -> None:
        self._ledger = ledger

    def _log(self, purpose: str) -> None:
        if self._ledger:
            self._ledger({"provider": "fake", "model": "fake-deterministic", "key_slot": None, "purpose": purpose,
                          "ok": True, "status": 200, "input_tokens": 0, "output_tokens": 0, "latency_ms": 0,
                          "error": None})

    def remaining(self, kind: str) -> int:
        return 1_000_000

    def images_per_request(self) -> int:
        return 3

    def analyze(self, items: Sequence[VisionInput], categories: Sequence[str],
                protected: Sequence[str]) -> list[Analysis]:
        self._log("analyze")
        return [normalize_analysis(self._label(item, categories, protected), categories) for item in items]

    def _label(self, item: VisionInput, categories: Sequence[str], protected: Sequence[str]) -> dict[str, Any]:
        words = set(tokens(" ".join(item.hints)))
        has_text = bool(words & {"meme", "text", "quote"}) or len(tokens(item.ocr_text)) >= 2
        minors = bool(words & {"baby", "kid", "kids", "child", "children", "school"})
        figure = next((name for name in protected if set(normalize_name(name).split()) <= words), "")
        if not figure and words & {"celebrity", "actor"}:
            figure = "Famous Actor"
        kind = ("baby" if "baby" in words else "animal" if words & {"cat", "dog", "puppy", "kitten", "animal"}
                else "cartoon" if "cartoon" in words else "couple" if "couple" in words
                else "meme" if has_text else "reaction")
        wanted = ("adult" if words & {"adult", "nonveg"} else "baby" if kind == "baby"
                  else "animals" if kind == "animal" else "office" if "office" in words
                  else "funny" if "funny" in words else "memes" if has_text else "reactions")
        category = wanted if wanted in categories else categories[0]
        mood = MOODS[_digest(item.ref, "mood") % len(MOODS)]
        score = 2 if words & {"blurry", "lowscore"} else max(3, min(9, round(item.prescreen_score)))
        best = ("skip" if "skip" in words else "direct" if has_text else "cutout" if "cutout" in words else "caption")
        tag_words = [w for w in sorted(words) if len(w) > 2 and w not in _STOP and not w.isdigit()]
        return {
            "kind": kind, "has_text": has_text, "text": item.ocr_text[:120] if has_text else "",
            "language": "hinglish" if has_text else "none", "category": category, "mood": [mood],
            "tags": [*tag_words[:8], mood, HINGLISH_MOOD[mood], kind, category],
            "emojis": [MOOD_EMOJI[mood]],
            "description": f"{kind} picture with a {mood} vibe",
            "nsfw": "explicit" if words & {"explicit", "nsfw", "nude"} else
                    "suggestive" if words & {"suggestive", "spicy"} else "none",
            "minors_likely": minors, "real_person": bool(words & {"person", "selfie", "celebrity", "actor"}) or
                                                    bool(figure),
            "public_figure": figure, "violence_or_gore": "graphic" if "gore" in words else "none",
            "hate_or_slur": bool(words & {"hate", "slur"}), "sticker_score": score, "best_recipe": best,
            "crop": FULL_CROP if has_text else SUBJECT_CROP,
            "caption_ideas": [] if has_text else [GENERAL_LINES[(_digest(item.ref) + i) % len(GENERAL_LINES)]
                                                  for i in range(3)],
        }

    def write_captions(self, request: CaptionRequest) -> list[Caption]:
        self._log("caption")
        analysis = request.analysis
        seed = _digest(analysis.description, *analysis.tags)
        avoid = set(request.avoid)
        lines = [line for line in GENERAL_LINES if line not in avoid] or list(GENERAL_LINES)
        captions = [Caption(lines[(seed + i) % len(lines)], "hinglish", "reaction", "general", analysis.emojis[:1])
                    for i in range(request.general)]
        if "hindi" in analysis.tags:
            captions.append(Caption(HINDI_LINES[seed % len(HINDI_LINES)], "hi", "desi", "general"))
        if request.adult and request.adult_styles:
            style = request.adult_styles[seed % len(request.adult_styles)]
            pool = SAVAGE_LINES if style in ("savage", "dark") else ADULT_LINES
            captions.append(Caption(pool[seed % len(pool)], "hinglish", style, "adult"))
        trend = request.trends[0] if request.trends else None
        if trend is not None:
            captions.append(Caption(f"{trend.title[:24]} wala scene", "hinglish", "trend", "general", (),
                                    trend.title))
        return captions

    def judge_captions(self, analysis: Analysis, captions: Sequence[Caption]) -> list[bool]:
        self._log("judge")
        return [not (lexical_flags(c.text) & {"explicit", "slur"}) for c in captions]

    def name_pack(self, request: PackNameRequest) -> PackName:
        from ..naming import fallback_pack_name

        self._log("pack_title")
        return fallback_pack_name(request.category, request.kind, request.rating, request.trend)
