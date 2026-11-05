"""Deterministic, Hinglish-friendly pack names used when the AI is unavailable."""
from __future__ import annotations

from .ai.base import PackName

CATEGORY_TITLES = {
    "reactions": "Reaction Dhamaka",
    "funny": "Hasi Ke Patakhe",
    "hinglish": "Hinglish Tadka",
    "movies": "Filmy Dialogues",
    "old_movies": "Purani Filmein, Naye Memes",
    "songs": "Gaane Wale Moods",
    "memes": "Meme Ki Dukaan",
    "dark": "Dark Mode Jokes",
    "relationship": "Pyaar Aur Lafde",
    "office": "Office Ke Dukh",
    "festivals": "Tyohaar Vibes",
    "animals": "Janwar Gang",
    "baby": "Chhote Ustad",
    "trending": "Aaj Ka Trend",
    "adult": "Non-Veg Jokes",
}


def fallback_pack_name(category: str, kind: str, rating: str, trend: str = "") -> PackName:
    if trend:
        base = f"Trending: {trend}"[:40]
        description = f"Aaj ke trend '{trend}' par fresh stickers"[:120]
    else:
        base = CATEGORY_TITLES.get(category, category.replace("_", " ").title() or "Sticker Mix")
        description = f"Fresh {category.replace('_', ' ')} stickers, updated as new ones arrive"[:120]
    if kind == "animated":
        base = f"{base} (Moving)"
    if rating == "adult":
        base = f"18+ {base}"
    return PackName(base[:60], description)


def volume_title(title: str, volume: int) -> str:
    return title if volume <= 1 else f"{title} Vol {volume}"[:128]
