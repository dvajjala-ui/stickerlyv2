"""Caption sourcing for the caption/cutout/clip recipes: AI text model, safety judge, fallbacks."""
from __future__ import annotations

from collections.abc import Sequence
from dataclasses import dataclass

from .. import vocab
from ..ai.base import AIError, CaptionRequest, TrendBrief
from ..analysis import Analysis, Caption
from ..imaging.text import complex_shaping_available
from ..policy import involves_minor, screen_caption
from ..textutil import has_devanagari
from .context import Context

ADULT_CATEGORIES = frozenset({"adult", "dark", "relationship"})
SEXUAL_OK_STYLES = ("double_meaning", "flirty", "savage", "dark")


@dataclass(frozen=True)
class CaptionSet:
    captions: tuple[Caption, ...]
    judged: bool
    origin: str  # ai | ideas | none


def adult_styles(analysis: Analysis) -> tuple[str, ...]:
    return vocab.NON_SEXUAL_ADULT_STYLES if analysis.real_person else SEXUAL_OK_STYLES


def _ideas(analysis: Analysis) -> list[Caption]:
    return [Caption(text, "hinglish", "reaction", "general", analysis.emojis[:1], origin="ideas")
            for text in analysis.caption_ideas]


def choose(captions: Sequence[Caption], limit: int = 2) -> tuple[Caption, ...]:
    """One general caption first (romanised preferred), then at most one adult variant."""
    general = sorted((c for c in captions if c.rating == "general"), key=lambda c: has_devanagari(c.text))
    adult = [c for c in captions if c.rating == "adult"]
    picked = general[:1] + adult[:1]
    if len(picked) < limit:
        picked += [c for c in general[1:] if c not in picked][: limit - len(picked)]
    return tuple(picked[:limit])


def get_captions(ctx: Context, analysis: Analysis, trends: Sequence[TrendBrief],
                 avoid: Sequence[str] = ()) -> CaptionSet:
    allow_adult = (ctx.settings.publish.adult_lane and not involves_minor(analysis)
                   and analysis.category in ADULT_CATEGORIES)
    captions: list[Caption] = []
    origin = "none"
    if ctx.ai is not None and ctx.ai.remaining("text") > 0:
        try:
            captions = ctx.ai.write_captions(CaptionRequest(
                analysis, tuple(trends), general=2, adult=1 if allow_adult else 0,
                adult_styles=adult_styles(analysis) if allow_adult else (), avoid=tuple(avoid)))
            origin = "ai"
        except AIError as error:
            ctx.log(f"captions: falling back to caption ideas ({error})")
    if not captions:
        captions, origin = _ideas(analysis), "ideas" if analysis.caption_ideas else "none"
    # Hard rules first (cheap, deterministic), then the AI judge on what is left.
    captions = [c for c in captions if screen_caption(analysis, c, ctx.protected, ctx.settings.publish).allowed]
    if not complex_shaping_available():  # without raqm, Devanagari conjuncts would render wrongly
        captions = [c for c in captions if not has_devanagari(c.text)]
    judged = True
    if captions:
        try:
            verdicts = ctx.ai.judge_captions(analysis, captions) if ctx.ai is not None else None
        except AIError as error:
            ctx.log(f"captions: judge unavailable ({error}); stickers stay draft")
            verdicts = None
        if verdicts is None:
            judged = False
        else:
            captions = [c for c, ok in zip(captions, verdicts, strict=True) if ok]
    return CaptionSet(choose(captions), judged, origin)
