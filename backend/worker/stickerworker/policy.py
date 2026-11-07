"""Content-safety policy: pure functions, no I/O. Owner decisions are encoded here.

Hard rules (never adjustable): no sexually explicit material, no hate/slurs, no
graphic violence, no protected people (court orders), never sexualise a real
person, and anything showing a minor is never adult and never carries adult text.
Adult lane = adult *humour* (double meaning, flirty, savage, dark) on clothed art.
Collected memes may be published as found (with credit) when quality allows;
everything else becomes a draft for admin review.
"""
from __future__ import annotations

from collections.abc import Iterable, Sequence
from dataclasses import dataclass
from difflib import SequenceMatcher

from . import vocab
from .analysis import Analysis, Caption
from .lexicon import lexical_flags
from .settings import PublishPolicy
from .textutil import normalize_name

# Catch-phrases and nicknames named in the court orders, keyed to the protected name.
ALIASES: dict[str, str] = {
    "big b": "Amitabh Bachchan",
    "jhakaas": "Anil Kapoor",
    "jhakas": "Anil Kapoor",
    "jhakkas": "Anil Kapoor",
    "bhidu": "Jackie Shroff",
    "jaggu dada": "Jackie Shroff",
}

ADULT_SIGNALS = frozenset({"suggestive", "profanity"})


@dataclass(frozen=True)
class Verdict:
    allowed: bool
    reasons: tuple[str, ...] = ()


@dataclass(frozen=True)
class Decision:
    status: str  # published | draft | rejected
    rating: str  # general | adult
    reasons: tuple[str, ...] = ()

    @property
    def rejected(self) -> bool:
        return self.status == "rejected"


# ------------------------------------------------------------------ protected people
def _close(a: str, b: str, threshold: float) -> bool:
    return SequenceMatcher(None, a, b).ratio() >= threshold


def name_matches(candidate: str, protected_name: str) -> bool:
    """Case-insensitive, honorific-free, typo-tolerant person-name match."""
    cand, prot = normalize_name(candidate), normalize_name(protected_name)
    if not cand or not prot:
        return False
    if cand == prot:
        return True
    ct, pt = cand.split(), prot.split()
    if set(pt) <= set(ct):
        return True  # "shri amitabh harivansh bachchan"
    if len(ct) >= 2 and set(ct) <= set(pt):
        return True  # "aishwarya rai" for "aishwarya rai bachchan"
    if _close(cand, prot, 0.88):
        return True  # "amitab bachan"
    if len(ct) >= 2 and all(any(_close(p, c, 0.8) for c in ct) for p in pt):
        return True  # "hritik roshen"
    # A lone name ("Hrithik", "Kapoor") is matched conservatively: court orders win over recall.
    return len(ct) == 1 and len(ct[0]) >= 4 and any(_close(ct[0], p, 0.9) for p in pt)


def mentions_protected(text: str, protected: Sequence[str]) -> str | None:
    """Full protected names (or court-order aliases) inside free text."""
    normalized = normalize_name(text)
    if not normalized:
        return None
    words = normalized.split()
    padded = f" {normalized} "
    for name in protected:
        target = normalize_name(name).split()
        if not target:
            continue
        spans = {len(target)} | ({len(target) - 1} if len(target) >= 3 else set())
        for span in spans:
            goal = " ".join(target[:span])
            for i in range(len(words) - span + 1):
                window = " ".join(words[i:i + span])
                if window == goal or (span >= 2 and _close(window, goal, 0.9)):
                    return name
    wanted = {normalize_name(n): n for n in protected}
    for alias, canonical in ALIASES.items():
        if f" {alias} " in padded and normalize_name(canonical) in wanted:
            return wanted[normalize_name(canonical)]
    return None


def find_protected(analysis: Analysis, protected: Sequence[str], extra: Iterable[str] = ()) -> str | None:
    if analysis.public_figure:
        for name in protected:
            if name_matches(analysis.public_figure, name):
                return name
    texts = [analysis.public_figure, analysis.text, analysis.description, " ".join(analysis.tags), *extra]
    for text in texts:
        found = mentions_protected(text or "", protected)
        if found:
            return found
    return None


# ------------------------------------------------------------------ image level
def involves_minor(analysis: Analysis) -> bool:
    return analysis.minors_likely or analysis.kind == "baby" or analysis.category == "baby"


def screen_source(analysis: Analysis, protected: Sequence[str], policy: PublishPolicy) -> Verdict:
    """Image-level hard rules. A rejected source produces no sticker at all."""
    reasons: list[str] = []
    text_flags = lexical_flags(analysis.text)
    if analysis.nsfw == "explicit":
        reasons.append("explicit_image")
    if "explicit" in text_flags:
        reasons.append("explicit_text")
    if analysis.hate_or_slur:
        reasons.append("hate_or_slur")
    if "slur" in text_flags:
        reasons.append("slur_text")
    if analysis.violence_or_gore == "graphic":
        reasons.append("graphic_violence")
    person = find_protected(analysis, protected)
    if person:
        reasons.append(f"protected_person:{person}")
    elif analysis.public_figure and policy.celebrities == "block":
        reasons.append("celebrity_blocked")
    if analysis.real_person and (analysis.nsfw != "none" or "suggestive" in text_flags):
        reasons.append("real_person_sexualised")
    if involves_minor(analysis) and (analysis.nsfw != "none" or analysis.category in vocab.MINOR_BLOCKED_CATEGORIES
                                     or text_flags & (ADULT_SIGNALS | {"explicit"})):
        reasons.append("minor_unsafe")
    if analysis.nsfw == "suggestive" and not policy.adult_lane:
        reasons.append("adult_lane_off")
    return Verdict(not reasons, tuple(dict.fromkeys(reasons)))


# ------------------------------------------------------------------ caption level
def caption_flags(caption: Caption) -> frozenset[str]:
    return lexical_flags(caption.text)


def is_sexual_caption(caption: Caption) -> bool:
    return caption.style in vocab.SEXUAL_STYLES or bool(caption_flags(caption) & {"suggestive", "explicit"})


def is_adult_caption(caption: Caption) -> bool:
    return caption.rating == "adult" or is_sexual_caption(caption) or bool(caption_flags(caption) & ADULT_SIGNALS)


def screen_caption(analysis: Analysis, caption: Caption, protected: Sequence[str],
                   policy: PublishPolicy) -> Verdict:
    """Caption-level hard rules; a rejected caption only drops that sticker variant."""
    reasons: list[str] = []
    flags = caption_flags(caption)
    if "explicit" in flags:
        reasons.append("explicit_caption")
    if "slur" in flags:
        reasons.append("slur_caption")
    if mentions_protected(caption.text, protected):
        reasons.append("protected_person_caption")
    if analysis.real_person and is_sexual_caption(caption):
        reasons.append("real_person_sexualised")
    if involves_minor(analysis) and is_adult_caption(caption):
        reasons.append("minor_adult_caption")
    if is_adult_caption(caption) and not policy.adult_lane:
        reasons.append("adult_lane_off")
    return Verdict(not reasons, tuple(reasons))


# ------------------------------------------------------------------ rating + status
def rate(analysis: Analysis, caption: Caption | None) -> str:
    """`adult` only for adult humour/categories; minors never get adult (they are rejected upstream)."""
    adult = (analysis.category == "adult" or analysis.nsfw == "suggestive"
             or bool(lexical_flags(analysis.text) & ADULT_SIGNALS)
             or (caption is not None and is_adult_caption(caption)))
    return "adult" if adult and not involves_minor(analysis) else "general"


def decide(analysis: Analysis, caption: Caption | None, *, protected: Sequence[str], policy: PublishPolicy,
           quality: int, site: str, judged: bool = True, adult_floor: bool = False) -> Decision:
    """Final verdict for one sticker variant: status, rating and machine-readable reasons.

    `adult_floor` (set when the source came from an 18+ collection topic) keeps the sticker out of the general lane:
    its rating floors at `adult`, so adult-humour content can't slip into general packs just because the model picked a
    non-adult category.
    """
    source = screen_source(analysis, protected, policy)
    if not source.allowed:
        return Decision("rejected", "general", source.reasons)
    if caption is not None:
        verdict = screen_caption(analysis, caption, protected, policy)
        if not verdict.allowed:
            return Decision("rejected", "general", verdict.reasons)
    rating = rate(analysis, caption)
    if adult_floor and rating == "general" and not involves_minor(analysis):
        rating = "adult"
    if rating == "adult" and involves_minor(analysis):  # belt and braces; screen_* already reject
        return Decision("rejected", "general", ("minor_unsafe",))
    if rating == "adult" and not policy.adult_lane:
        return Decision("rejected", "general", ("adult_lane_off",))
    review: list[str] = []
    if caption is not None and not judged:
        review.append("caption_not_judged")
    if not policy.auto_publish:
        review.append("auto_publish_off")
    if quality < policy.min_quality:
        review.append("below_min_quality")
    if site.strip().lower() in policy.require_review_sites:
        review.append("site_requires_review")
    if analysis.public_figure and policy.celebrities == "review":
        review.append("celebrity_review")
    return Decision("draft" if review else "published", rating, tuple(review))
