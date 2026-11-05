"""Free, local quality signals: pHash, sharpness, contrast, aspect, coverage, score."""
from __future__ import annotations

import math

import imagehash
import numpy as np
from PIL import Image

from .decode import flatten


def phash_hex(image: Image.Image) -> str:
    """64-bit perceptual hash as 16 lowercase hex characters."""
    return str(imagehash.phash(flatten(image), hash_size=8))


def hamming(a: str, b: str) -> int:
    return (int(a, 16) ^ int(b, 16)).bit_count()


def _gray(image: Image.Image, max_side: int = 512) -> np.ndarray:
    rgb = flatten(image)
    rgb.thumbnail((max_side, max_side), Image.Resampling.BILINEAR)
    return np.asarray(rgb.convert("L"), dtype=np.float32)


def laplacian_variance(gray: np.ndarray) -> float:
    if gray.shape[0] < 3 or gray.shape[1] < 3:
        return 0.0
    lap = (-4 * gray[1:-1, 1:-1] + gray[:-2, 1:-1] + gray[2:, 1:-1] + gray[1:-1, :-2] + gray[1:-1, 2:])
    return float(lap.var())


def measure(image: Image.Image) -> dict[str, float]:
    width, height = image.size
    gray = _gray(image)
    alpha = np.asarray(image.getchannel("A"), dtype=np.uint8) if image.mode == "RGBA" else None
    rgb = np.asarray(flatten(image).resize((128, 128)), dtype=np.float32)
    rg = rgb[..., 0] - rgb[..., 1]
    yb = 0.5 * (rgb[..., 0] + rgb[..., 1]) - rgb[..., 2]
    colorfulness = math.hypot(float(rg.std()), float(yb.std())) + 0.3 * math.hypot(float(rg.mean()), float(yb.mean()))
    return {
        "width": float(width),
        "height": float(height),
        "short_side": float(min(width, height)),
        "aspect": round(max(width, height) / max(1, min(width, height)), 3),
        "sharpness": round(laplacian_variance(gray), 2),
        "contrast": round(float(gray.std()), 2),
        "brightness": round(float(gray.mean()), 2),
        "colorfulness": round(colorfulness, 2),
        "coverage": round(float((alpha > 16).mean()) if alpha is not None else 1.0, 4),
    }


def prescreen_score(m: dict[str, float]) -> float:
    """0-10 heuristic: resolution 30%, sharpness 30%, contrast 20%, aspect 20%."""
    resolution = min(1.0, m["short_side"] / 512.0)
    sharp = min(1.0, max(0.0, (math.log10(max(m["sharpness"], 1.0)) - 1.0) / 1.5))
    contrast = min(1.0, m["contrast"] / 60.0)
    aspect = 1.0 if m["aspect"] <= 1.6 else max(0.0, 1.0 - (m["aspect"] - 1.6) / 1.9)
    return round(10.0 * (0.3 * resolution + 0.3 * sharp + 0.2 * contrast + 0.2 * aspect), 2)


def frame_stats(image: Image.Image) -> tuple[float, float]:
    """(coverage, detail) of a frame: opaque fraction and luminance spread of opaque pixels."""
    small = image.convert("RGBA").resize((128, 128), Image.Resampling.BILINEAR)
    array = np.asarray(small)
    opaque = array[..., 3] > 24
    if not opaque.any():
        return 0.0, 0.0
    gray = np.asarray(small.convert("L"), dtype=np.float32)
    return float(opaque.mean()), float(gray[opaque].std())
