"""Small, dependency-free text helpers (cleaning, tokens, emoji, scripts)."""
from __future__ import annotations

import re
import unicodedata
from collections.abc import Iterable
from typing import Any

# Control and bidi-override characters. ZWJ/ZWNJ (U+200C/U+200D) are kept: they matter
# for Devanagari conjuncts and emoji sequences.
_CONTROL = re.compile(r"[\x00-\x08\x0b-\x1f\x7f-\x9f\u200b\u200e\u200f\u202a-\u202e\u2066-\u2069\ufeff]")
_DEVANAGARI = re.compile(r"[\u0900-\u097f\ua8e0-\ua8ff]")
_WORD = re.compile(r"[0-9A-Za-z\u00c0-\u024f\u0900-\u097f\ua8e0-\ua8ff']+")

_EMOJI_BASE = (
    "\u00a9\u00ae\u203c\u2049\u2122\u2139\u2194-\u2199\u21a9\u21aa\u231a\u231b\u2328\u23cf"
    "\u23e9-\u23f3\u23f8-\u23fa\u24c2\u25aa\u25ab\u25b6\u25c0\u25fb-\u25fe\u2600-\u27bf"
    "\u2934\u2935\u2b05-\u2b07\u2b1b\u2b1c\u2b50\u2b55\u3030\u303d\u3297\u3299"
    "\U0001F000-\U0001FAFF"
)
_EMOJI_MOD = "\ufe0f\U0001F3FB-\U0001F3FF"
EMOJI_RE = re.compile(
    "(?:[\U0001F1E6-\U0001F1FF]{2})"
    "|(?:[0-9#*]\ufe0f?\u20e3)"
    f"|(?:[{_EMOJI_BASE}][{_EMOJI_MOD}]*(?:\u200d[{_EMOJI_BASE}][{_EMOJI_MOD}]*)*)"
)
_HONORIFICS = frozenset({"mr", "mrs", "ms", "miss", "shri", "sri", "smt", "dr", "ji", "sir", "sahab", "saab"})


def clean_text(value: Any, limit: int) -> str:
    """Normalise to NFC, drop control characters, collapse whitespace, truncate."""
    if value is None:
        return ""
    text = unicodedata.normalize("NFC", value if isinstance(value, str) else str(value))
    text = _CONTROL.sub(" ", text)
    return " ".join(text.split())[:limit].strip()


def has_devanagari(text: str) -> bool:
    return bool(_DEVANAGARI.search(text or ""))


def tokens(text: str) -> list[str]:
    """Lower-case word tokens; keeps Devanagari letters together with their vowel signs."""
    return [t.strip("'") for t in _WORD.findall((text or "").lower()) if t.strip("'")]


def is_emoji(value: str) -> bool:
    return bool(value) and EMOJI_RE.fullmatch(value) is not None


def emoji_list(values: Any, limit: int = 3) -> tuple[str, ...]:
    """Extract up to `limit` distinct emoji from a string or a list of strings."""
    if isinstance(values, str):
        values = [values]
    if not isinstance(values, Iterable):
        return ()
    found: list[str] = []
    for value in values:
        for match in EMOJI_RE.finditer(str(value)):
            emoji = match.group(0)
            if emoji not in found:
                found.append(emoji)
            if len(found) >= limit:
                return tuple(found)
    return tuple(found)


def strip_emoji(text: str) -> str:
    return " ".join(EMOJI_RE.sub(" ", text or "").replace("\ufe0f", "").split())


def clean_tag(value: Any) -> str:
    tag = clean_text(value, 40).lower().lstrip("#")
    tag = strip_emoji(tag)
    tag = re.sub(r"[^\w\u0900-\u097f\ua8e0-\ua8ff' -]", "", tag)
    return " ".join(tag.replace("_", " ").split())[:30]


def unique(values: Iterable[str], limit: int | None = None) -> tuple[str, ...]:
    seen: list[str] = []
    for value in values:
        if value and value not in seen:
            seen.append(value)
        if limit is not None and len(seen) >= limit:
            break
    return tuple(seen)


def normalize_name(value: str) -> str:
    """Lower-case ASCII-folded person name without honorifics or punctuation."""
    folded = unicodedata.normalize("NFKD", value or "")
    folded = "".join(ch for ch in folded if not unicodedata.combining(ch)).lower()
    folded = re.sub(r"[^0-9a-z\u0900-\u097f]+", " ", folded)
    return " ".join(part for part in folded.split() if part not in _HONORIFICS)


def slugify(value: str, limit: int = 60) -> str:
    folded = unicodedata.normalize("NFKD", value or "")
    folded = "".join(ch for ch in folded if not unicodedata.combining(ch)).lower()
    slug = re.sub(r"[^a-z0-9]+", "-", folded).strip("-")
    return slug[:limit].strip("-") or "pack"
