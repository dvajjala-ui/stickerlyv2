"""Which sticker recipes to run for an analysed source (pure)."""
from __future__ import annotations

from dataclasses import dataclass

from ..analysis import Analysis

MIN_SCORE = 3
MOTION_MIN_SCORE = 6


@dataclass(frozen=True)
class Plan:
    recipes: tuple[str, ...]
    reason: str = ""


def plan_recipes(analysis: Analysis, *, animated_source: bool, cutout_available: bool) -> Plan:
    """direct for pictures that already carry text (never caption over text); caption for reactions;
    cutout when asked for and installed; clip for GIF/video; motion variants for strong stickers."""
    if analysis.best_recipe == "skip":
        return Plan((), "model_skip")
    if analysis.sticker_score < MIN_SCORE:
        return Plan((), "low_sticker_score")
    if animated_source:
        return Plan(("clip",))
    if analysis.has_text or analysis.best_recipe == "direct":
        base = "direct"
    elif analysis.best_recipe == "cutout" and cutout_available:
        base = "cutout"
    else:
        base = "caption"
    if analysis.sticker_score >= MOTION_MIN_SCORE:
        return Plan((base, "motion"))
    return Plan((base,))
