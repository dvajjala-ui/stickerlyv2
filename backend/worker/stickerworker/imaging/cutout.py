"""Optional subject cut-out via rembg (extra `cutout`).

Only commercially usable models are allowed. rembg's default model is never used:
a model name is always passed explicitly and checked against this allowlist.
"""
from __future__ import annotations

import importlib.util
from functools import lru_cache
from typing import Any

from PIL import Image

ALLOWED_MODELS = ("birefnet-general", "isnet-general-use")


class CutoutUnavailable(RuntimeError):
    pass


def available() -> bool:
    return importlib.util.find_spec("rembg") is not None


@lru_cache(maxsize=2)
def _session(model: str) -> Any:
    if model not in ALLOWED_MODELS:
        raise CutoutUnavailable(f"model {model!r} is not allowed; use one of {ALLOWED_MODELS}")
    if not available():
        raise CutoutUnavailable("rembg is not installed (pip install '.[cutout]')")
    from rembg import new_session  # type: ignore[import-not-found]

    try:
        return new_session(model)  # downloads the model on first use
    except Exception as error:  # noqa: BLE001
        raise CutoutUnavailable(f"rembg session failed: {type(error).__name__}") from None


def cut_subject(image: Image.Image, model: str) -> Image.Image:
    """Return the subject on a transparent background; raises CutoutUnavailable."""
    session = _session(model)
    from rembg import remove  # type: ignore[import-not-found]

    try:
        result = remove(image.convert("RGBA"), session=session)
    except Exception as error:  # noqa: BLE001 - onnxruntime/model errors: fall back to captions
        raise CutoutUnavailable(f"rembg failed: {type(error).__name__}") from None
    if not isinstance(result, Image.Image):
        raise CutoutUnavailable("unexpected rembg result")
    rgba = result.convert("RGBA")
    if rgba.getchannel("A").getbbox() is None:
        raise CutoutUnavailable("no subject found")
    return rgba
