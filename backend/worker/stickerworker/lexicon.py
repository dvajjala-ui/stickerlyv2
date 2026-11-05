"""Lexical backstop for captions and transcribed image text.

The AI analysis and the caption judge are the primary safety signals; these short
lists only catch obvious cases deterministically (and work offline). Terms are
matched as whole tokens (with simple leetspeak/repeat folding); Devanagari stems
are matched as substrings because Hindi compounds them.
"""
from __future__ import annotations

import re

# Sexually explicit words (English + romanised Hindi). Always rejected.
_EXPLICIT = {
    "porn", "porno", "pornhub", "xxx", "nude", "nudes", "naked", "nangi", "nanga", "boobs", "boobies",
    "tits", "titties", "pussy", "dick", "dicks", "cock", "penis", "vagina", "blowjob", "handjob",
    "cumshot", "orgasm", "masturbate", "masturbation", "rape", "rapist", "chut", "choot", "lund",
    "lauda", "lawda", "lavda", "loda", "chudai", "chod", "chodna", "chodu", "bhosda", "bhosdi",
    "bhosdike", "bhosadi", "madarchod", "maderchod", "behenchod", "bhenchod", "behanchod",
}
_EXPLICIT_PHRASES = ("blow job", "hand job")
_EXPLICIT_DEVANAGARI = ("चूत", "लंड", "लौड़ा", "लौडा", "चोद", "भोसड", "नंगी", "बलात्कार")

# Innuendo / sexual-adjacent words: adult lane only, never with minors or real people.
_SUGGESTIVE = {
    "sexy", "sex", "horny", "tharki", "thharki", "suhagraat", "condom", "condoms", "virgin", "booty",
    "seduce", "seductive", "nonveg", "bikini", "lingerie", "chutad",
}
_SUGGESTIVE_PHRASES = ("non veg", "double meaning", "suhaag raat", "first night")
_SUGGESTIVE_DEVANAGARI = ("सेक्स", "सुहागरात", "ठरकी")

# Non-sexual profanity: allowed only in the adult lane.
_PROFANITY = {
    "bc", "mc", "bsdk", "bkl", "chutiya", "chutiye", "chutiyapa", "gaandu", "gandu", "harami",
    "haramkhor", "kamina", "kamine", "jhaat", "jhatu", "lodu", "fuck", "fucking", "fck", "wtf",
    "shit", "bitch", "bastard", "asshole", "motherfucker",
}

# Slurs against caste, religion, region, gender or ethnicity. Always rejected.
_SLURS = {
    "chamar", "chamaar", "bhangi", "chuhra", "katua", "katwa", "kattua", "mulle", "sulla", "sulle",
    "jihadi", "chinki", "chinky", "madrasi", "randi", "hijda", "hijde", "nigger", "nigga",
    "faggot", "fag", "tranny", "retard", "retarded", "paki", "kike", "spic", "chink", "gook",
}
_SLUR_PHRASES = ("neech jaat", "neechi jaat")
_SLUR_DEVANAGARI = ("चमार", "भंगी", "कटुआ", "रंडी", "हिजड़ा", "हिजडा")

_LEET = str.maketrans({"0": "o", "1": "i", "3": "e", "4": "a", "5": "s", "7": "t", "@": "a", "$": "s"})
_TOKEN = re.compile(r"[a-z0-9@$]+")


def _collapse(token: str) -> str:
    return re.sub(r"(.)\1+", r"\1", token)


def _folded(words: set[str]) -> frozenset[str]:
    return frozenset(words | {_collapse(w) for w in words})


EXPLICIT = _folded(_EXPLICIT)
SUGGESTIVE = _folded(_SUGGESTIVE)
PROFANITY = _folded(_PROFANITY)
SLURS = _folded(_SLURS)


def lexical_flags(text: str) -> frozenset[str]:
    """Return a subset of {"explicit", "suggestive", "profanity", "slur"}."""
    if not text:
        return frozenset()
    lowered = text.lower()
    raw = _TOKEN.findall(lowered)
    words = {w.translate(_LEET) for w in raw} | set(raw)
    words |= {_collapse(w) for w in words}
    joined = " " + " ".join(w.translate(_LEET) for w in raw) + " "
    flags: set[str] = set()
    if words & EXPLICIT or any(f" {p} " in joined for p in _EXPLICIT_PHRASES) \
            or any(stem in text for stem in _EXPLICIT_DEVANAGARI):
        flags.add("explicit")
    if words & SUGGESTIVE or "18+" in lowered or any(f" {p} " in joined for p in _SUGGESTIVE_PHRASES) \
            or any(stem in text for stem in _SUGGESTIVE_DEVANAGARI):
        flags.add("suggestive")
    if words & PROFANITY:
        flags.add("profanity")
    if words & SLURS or any(f" {p} " in joined for p in _SLUR_PHRASES) \
            or any(stem in text for stem in _SLUR_DEVANAGARI):
        flags.add("slur")
    return frozenset(flags)
