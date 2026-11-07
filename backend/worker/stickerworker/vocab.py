"""Shared vocabulary used by the AI schemas, the policy and the database rows."""
from __future__ import annotations

KINDS = ("meme", "reaction", "scene", "cartoon", "text_post", "animal", "baby", "couple", "other")
LANGUAGES = ("en", "hi", "hinglish", "other", "none")
CAPTION_LANGUAGES = ("en", "hi", "hinglish")
NSFW_LEVELS = ("none", "suggestive", "explicit")
VIOLENCE_LEVELS = ("none", "mild", "graphic")
BEST_RECIPES = ("direct", "caption", "cutout", "skip")
RATINGS = ("general", "adult")

CAPTION_STYLES = (
    "reaction", "sarcastic", "savage", "wholesome", "desi", "filmy", "office",
    "flirty", "double_meaning", "dark", "trend",
)
# Caption styles that are sexual or suggestive by nature (never on real people or minors).
SEXUAL_STYLES = frozenset({"flirty", "double_meaning"})
# Adult styles that are allowed on photos of real people (not sexual).
NON_SEXUAL_ADULT_STYLES = ("savage", "dark")

# Categories that can never carry an image where minors are likely.
MINOR_BLOCKED_CATEGORIES = frozenset({"adult", "dark", "relationship"})

# stickers.recipe values (DB check constraint).
STICKER_RECIPES = ("direct", "caption", "cutout", "motion", "remake", "original", "upload")
# stickers.status values (DB check constraint). `held` = below the quality gate, waiting for an admin.
STICKER_STATUSES = ("draft", "published", "held", "rejected", "taken_down")

SOURCE_STATUSES = ("new", "screened", "analyzing", "analyzed", "used", "rejected", "failed", "taken_down")
TEXT_PURPOSES = ("caption", "judge", "pack_title", "trend")
VISION_PURPOSE = "analyze"
# Optional vision quality judge: one image per call, counted against budgets.vision_per_day.
QUALITY_PURPOSE = "quality_judge"

# Collection topics whose content defaults to the adult lane (18+). A source whose topic_hint matches keeps its
# sticker out of the general lane (policy.decide adult_floor). Keys and their " "-spaced topic_hint forms.
ADULT_TOPIC_HINTS = frozenset({"adult humor", "adult humour", "dirty jokes", "dark memes"})
