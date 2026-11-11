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


def plan_recipes(analysis: Analysis, *, animated_source: bool, cutout_available: bool,
                 motion_enabled: bool = False, clip_enabled: bool = False) -> Plan:
    """direct for pictures that already carry text (never caption over text); caption for reactions;
    cutout when asked for and installed; clip for GIF/video; motion variants for strong stickers.

    Animated recipes are paused by owner decision (2026-11-11): `motion` made "growing image"
    animations and `clip` GIFs did not play properly. While paused, animated sources get no recipe
    (reason `animated_paused`) and static sources never get a motion variant.
    """
    if analysis.best_recipe == "skip":
        return Plan((), "model_skip")
    if analysis.sticker_score < MIN_SCORE:
        return Plan((), "low_sticker_score")
    if animated_source:
        return Plan(("clip",)) if clip_enabled else Plan((), "animated_paused")
    if analysis.has_text or analysis.best_recipe == "direct":
        base = "direct"
    elif analysis.best_recipe == "cutout" and cutout_available:
        base = "cutout"
    else:
        base = "caption"
    if motion_enabled and analysis.sticker_score >= MOTION_MIN_SCORE:
        return Plan((base, "motion"))
    return Plan((base,))
