"""Owner settings (the `settings` table) with the migration's defaults as fallback."""
from __future__ import annotations

from collections.abc import Mapping
from dataclasses import dataclass
from typing import Any

DEFAULT_ROWS: dict[str, Any] = {
    "publish_policy": {"auto_publish": True, "min_quality": 6, "adult_lane": True,
                       "celebrities": "publish", "require_review_sites": []},
    "budgets": {"vision_per_day": 60, "text_per_day": 400, "image_gen_per_day": 0,
                "max_new_stickers_per_day": 120},
    "categories": {"enabled": ["reactions", "funny", "hinglish", "movies", "old_movies", "songs", "memes",
                               "dark", "relationship", "office", "festivals", "animals", "baby",
                               "trending", "adult"]},
    "quality_gate": {"enabled": True, "min_score": 60, "vision_judge": False, "judge_band": 20},
}

# Mirrors the migration seed; the database table is authoritative in production.
DEFAULT_PROTECTED_PEOPLE: tuple[tuple[str, str], ...] = (
    ("Amitabh Bachchan", "Delhi HC 2022 order"),
    ("Anil Kapoor", "Delhi HC 2023 order (incl. GIFs, jhakaas)"),
    ("Aishwarya Rai Bachchan", "Delhi HC 2025 order"),
    ("Abhishek Bachchan", "Delhi HC 2025 order"),
    ("Karan Johar", "Delhi HC 2025 order"),
    ("Hrithik Roshan", "Delhi HC 2025 order"),
    ("Jackie Shroff", "Delhi HC 2024 order (memes partly allowed)"),
    ("Arijit Singh", "Bombay HC 2024 order"),
)

CELEBRITY_MODES = ("publish", "review", "block")


@dataclass(frozen=True)
class PublishPolicy:
    auto_publish: bool = True
    min_quality: int = 6
    adult_lane: bool = True
    celebrities: str = "publish"
    require_review_sites: frozenset[str] = frozenset()


@dataclass(frozen=True)
class Budgets:
    vision_per_day: int = 60
    text_per_day: int = 400
    image_gen_per_day: int = 0
    max_new_stickers_per_day: int = 120


@dataclass(frozen=True)
class QualityGate:
    """Stickers that would publish but score below `min_score` (0-100) are held for an admin.

    `vision_judge` (off by default) asks the vision model about stickers whose local score is
    within `judge_band` of `min_score`; it spends budgets.vision_per_day, one image per sticker.
    """
    enabled: bool = True
    min_score: int = 60
    vision_judge: bool = False
    judge_band: int = 20


@dataclass(frozen=True)
class Settings:
    publish: PublishPolicy
    budgets: Budgets
    categories: tuple[str, ...]
    quality: QualityGate = QualityGate()


def _int(value: Any, default: int, low: int = 0, high: int = 1_000_000) -> int:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        return default
    return max(low, min(high, int(value)))


def _bool(value: Any, default: bool) -> bool:
    return value if isinstance(value, bool) else default


def parse_settings(rows: Mapping[str, Any]) -> Settings:
    """Build typed settings; invalid or missing values fall back to the defaults."""
    raw_policy = rows.get("publish_policy")
    policy = raw_policy if isinstance(raw_policy, Mapping) else {}
    raw_budgets = rows.get("budgets")
    budgets = raw_budgets if isinstance(raw_budgets, Mapping) else {}
    raw_categories = rows.get("categories")
    categories = raw_categories.get("enabled") if isinstance(raw_categories, Mapping) else None
    raw_gate = rows.get("quality_gate")
    gate = raw_gate if isinstance(raw_gate, Mapping) else {}
    defaults = DEFAULT_ROWS
    sites = policy.get("require_review_sites")
    celebrities = policy.get("celebrities")
    enabled = tuple(dict.fromkeys(str(c).strip().lower() for c in categories if str(c).strip())) \
        if isinstance(categories, list) else ()
    return Settings(
        publish=PublishPolicy(
            auto_publish=_bool(policy.get("auto_publish"), True),
            min_quality=_int(policy.get("min_quality"), 6, 0, 10),
            adult_lane=_bool(policy.get("adult_lane"), True),
            celebrities=celebrities if celebrities in CELEBRITY_MODES else "publish",
            require_review_sites=frozenset(str(s).strip().lower() for s in sites if str(s).strip())
            if isinstance(sites, list) else frozenset(),
        ),
        budgets=Budgets(
            vision_per_day=_int(budgets.get("vision_per_day"), defaults["budgets"]["vision_per_day"]),
            text_per_day=_int(budgets.get("text_per_day"), defaults["budgets"]["text_per_day"]),
            image_gen_per_day=_int(budgets.get("image_gen_per_day"), 0),
            max_new_stickers_per_day=_int(budgets.get("max_new_stickers_per_day"),
                                          defaults["budgets"]["max_new_stickers_per_day"]),
        ),
        categories=enabled or tuple(defaults["categories"]["enabled"]),
        quality=QualityGate(
            enabled=_bool(gate.get("enabled"), True),
            min_score=_int(gate.get("min_score"), defaults["quality_gate"]["min_score"], 0, 100),
            vision_judge=_bool(gate.get("vision_judge"), False),
            judge_band=_int(gate.get("judge_band"), defaults["quality_gate"]["judge_band"], 0, 100),
        ),
    )
