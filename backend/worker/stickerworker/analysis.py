"""Typed AI understanding of an image, plus strict normalisation of model output.

Safety-critical fields must be present and valid; anything else is coerced or
defaulted. Stored rows are re-parsed with the same rules, so admin edits that
break the contract are caught rather than trusted.
"""
from __future__ import annotations

import math
from collections.abc import Mapping, Sequence
from dataclasses import asdict, dataclass
from typing import Any

from . import vocab
from .textutil import clean_tag, clean_text, emoji_list, strip_emoji, tidy_caption, tokens, unique

_FILLER_STOP = frozenset({"the", "and", "with", "for", "from", "this", "that", "his", "her", "are", "picture",
                          "image", "photo", "showing", "shows"})


class InvalidAnalysis(ValueError):
    """The model output (or a stored analysis) breaks the contract."""


@dataclass(frozen=True)
class Crop:
    x: float = 0.0
    y: float = 0.0
    w: float = 1.0
    h: float = 1.0

    @classmethod
    def parse(cls, value: Any) -> Crop:
        if not isinstance(value, Mapping):
            return cls()
        try:
            x, y, w, h = (float(value.get(k, d)) for k, d in (("x", 0), ("y", 0), ("w", 1), ("h", 1)))
        except (TypeError, ValueError):
            return cls()
        if not all(math.isfinite(v) for v in (x, y, w, h)):
            return cls()
        if 1.0 < max(x, y, w, h) <= 100.0:  # percentages
            x, y, w, h = (v / 100.0 for v in (x, y, w, h))
        x = min(max(x, 0.0), 0.95)
        y = min(max(y, 0.0), 0.95)
        w = min(max(w, 0.05), 1.0 - x)
        h = min(max(h, 0.05), 1.0 - y)
        return cls(round(x, 4), round(y, 4), round(w, 4), round(h, 4))

    @property
    def is_full(self) -> bool:
        return self.w >= 0.98 and self.h >= 0.98


@dataclass(frozen=True)
class Analysis:
    kind: str = "other"
    has_text: bool = False
    text: str = ""
    language: str = "none"
    category: str = "reactions"
    mood: tuple[str, ...] = ()
    tags: tuple[str, ...] = ()
    emojis: tuple[str, ...] = ()
    description: str = ""
    nsfw: str = "none"
    minors_likely: bool = False
    real_person: bool = False
    public_figure: str = ""
    violence_or_gore: str = "none"
    hate_or_slur: bool = False
    sticker_score: int = 0
    best_recipe: str = "caption"
    crop: Crop = Crop()
    caption_ideas: tuple[str, ...] = ()

    def to_json(self) -> dict[str, Any]:
        data = asdict(self)
        for key in ("mood", "tags", "emojis", "caption_ideas"):
            data[key] = list(data[key])
        return data


@dataclass(frozen=True)
class Caption:
    text: str
    language: str = "hinglish"
    style: str = "reaction"
    rating: str = "general"
    emojis: tuple[str, ...] = ()
    trend: str = ""
    origin: str = "ai"  # ai | ideas


def _enum(value: Any, allowed: Sequence[str]) -> str | None:
    text = str(value or "").strip().lower().replace(" ", "_").replace("-", "_")
    return text if text in allowed else None


def _required_bool(item: Mapping[str, Any], key: str) -> bool:
    value = item.get(key)
    if isinstance(value, bool):
        return value
    if isinstance(value, str) and value.strip().lower() in {"true", "false"}:
        return value.strip().lower() == "true"
    raise InvalidAnalysis(f"{key} must be a boolean")


def _violence(value: Any) -> str:
    if isinstance(value, bool):  # tolerate a boolean answer, conservatively
        return "graphic" if value else "none"
    level = _enum(value, vocab.VIOLENCE_LEVELS)
    if level is None:
        raise InvalidAnalysis("violence_or_gore must be none|mild|graphic")
    return level


def _strings(value: Any, limit: int, size: int) -> list[str]:
    if isinstance(value, str):
        value = [part for part in value.split(",")]
    if not isinstance(value, list):
        return []
    return [text for text in (clean_text(v, size) for v in value[: limit * 3]) if text][:limit * 2]


def normalize_analysis(item: Any, categories: Sequence[str]) -> Analysis:
    """Validate one analysis object. Raises InvalidAnalysis on safety-field problems."""
    if not isinstance(item, Mapping):
        raise InvalidAnalysis("analysis must be an object")
    nsfw = _enum(item.get("nsfw"), vocab.NSFW_LEVELS)
    if nsfw is None:
        raise InvalidAnalysis("nsfw must be none|suggestive|explicit")
    kind = _enum(item.get("kind"), vocab.KINDS) or "other"
    minors = _required_bool(item, "minors_likely")
    real_person = _required_bool(item, "real_person")
    hate = _required_bool(item, "hate_or_slur")
    has_text = _required_bool(item, "has_text")
    violence = _violence(item.get("violence_or_gore"))
    allowed = tuple(categories) or ("reactions",)
    category = _enum(item.get("category"), allowed) or ("baby" if kind == "baby" and "baby" in allowed
                                                       else allowed[0])
    language = _enum(item.get("language"), vocab.LANGUAGES) or ("none" if not has_text else "other")
    try:
        score = int(round(float(item.get("sticker_score", 0))))
    except (TypeError, ValueError):
        score = 0
    best = _enum(item.get("best_recipe"), vocab.BEST_RECIPES) or ("direct" if has_text else "caption")
    mood = unique((clean_tag(m) for m in _strings(item.get("mood"), 5, 30)), 5)
    description = clean_text(strip_emoji(clean_text(item.get("description"), 300)), 200)
    tags = list(unique((clean_tag(t) for t in _strings(item.get("tags"), 15, 40)), 15))
    if len(tags) < 5:  # keep search useful even when the model is terse
        filler = [*mood, kind.replace("_", " "), category.replace("_", " "), *tokens(description)]
        tags = list(unique([*tags, *(clean_tag(f) for f in filler if len(f) > 2 and f not in _FILLER_STOP)], 15))
    ideas = () if has_text else unique(
        (clean_text(strip_emoji(i), 60) for i in _strings(item.get("caption_ideas"), 3, 80)), 3)
    return Analysis(
        kind=kind,
        has_text=has_text,
        text=clean_text(item.get("text"), 500) if has_text else "",
        language=language,
        category=category,
        mood=mood,
        tags=tuple(tags),
        emojis=emoji_list(item.get("emojis"), 3),
        description=description,
        nsfw=nsfw,
        minors_likely=minors,
        real_person=real_person,
        public_figure=clean_text(item.get("public_figure"), 80),
        violence_or_gore=violence,
        hate_or_slur=hate,
        sticker_score=max(0, min(10, score)),
        best_recipe=best,
        crop=Crop.parse(item.get("crop")),
        caption_ideas=tuple(i for i in ideas if i),
    )


def normalize_batch(content: Any, categories: Sequence[str], expected: int) -> list[Analysis]:
    """Map a model batch {"images": [...]} back to input order (by `image` index when given)."""
    items = content.get("images") if isinstance(content, Mapping) else content
    if not isinstance(items, list) or len(items) != expected:
        raise InvalidAnalysis(f"expected {expected} analyses")
    ordered: list[Any] = [None] * expected
    indexed = all(isinstance(i, Mapping) and isinstance(i.get("image"), int) for i in items)
    if indexed and sorted(i["image"] for i in items) == list(range(expected)):
        for item in items:
            ordered[item["image"]] = item
    else:
        ordered = list(items)
    return [normalize_analysis(item, categories) for item in ordered]


def analysis_from_row(row: Mapping[str, Any], categories: Sequence[str]) -> Analysis:
    """Re-validate a stored source row's analysis (jsonb column)."""
    stored = row.get("analysis")
    if not isinstance(stored, Mapping):
        raise InvalidAnalysis("source has no analysis")
    return normalize_analysis(stored, categories)


def normalize_caption(item: Any, *, origin: str = "ai") -> Caption | None:
    if not isinstance(item, Mapping):
        return None
    text = tidy_caption(clean_text(strip_emoji(clean_text(item.get("text"), 160)), 90))
    if not text:
        return None
    return Caption(
        text=text,
        language=_enum(item.get("language"), vocab.CAPTION_LANGUAGES) or "hinglish",
        style=_enum(item.get("style"), vocab.CAPTION_STYLES) or "reaction",
        rating=_enum(item.get("rating"), vocab.RATINGS) or "general",
        emojis=emoji_list(item.get("emojis"), 3),
        trend=clean_text(item.get("trend"), 120),
        origin=origin,
    )
