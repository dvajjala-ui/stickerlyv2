"""Optional OCR (extra `ocr` + the tesseract binary). Returns None when unavailable."""
from __future__ import annotations

import importlib.util
import shutil
from functools import lru_cache

from PIL import Image, ImageOps

from .decode import flatten


@lru_cache(maxsize=1)
def languages() -> str | None:
    if importlib.util.find_spec("pytesseract") is None or shutil.which("tesseract") is None:
        return None
    import pytesseract  # type: ignore[import-not-found]

    try:
        installed = set(pytesseract.get_languages(config=""))
    except Exception:  # noqa: BLE001 - any tesseract failure means "no OCR"
        return None
    wanted = [lang for lang in ("eng", "hin") if lang in installed]
    return "+".join(wanted) if wanted else None


def read_text(image: Image.Image, limit: int = 1000) -> str | None:
    langs = languages()
    if langs is None:
        return None
    import pytesseract  # type: ignore[import-not-found]

    gray = ImageOps.autocontrast(flatten(image).convert("L"))
    if min(gray.size) < 600:
        scale = 600 / min(gray.size)
        gray = gray.resize((round(gray.width * scale), round(gray.height * scale)), Image.Resampling.LANCZOS)
    try:
        text = pytesseract.image_to_string(gray, lang=langs, timeout=20)
    except Exception:  # noqa: BLE001 - OCR is best effort
        return None
    words = [w for w in text.split() if sum(ch.isalnum() for ch in w) >= 2]
    return " ".join(words)[:limit] if words else ""
