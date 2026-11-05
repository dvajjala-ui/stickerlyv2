"""Free, local quality signals: pHash, sharpness, contrast, aspect, coverage, score."""
from __future__ import annotations

import math

import imagehash
import numpy as np
from PIL import Image

from .decode import flatten

# WhatsApp's default chat backgrounds (light, dark): what shows through a sticker's transparency.
CHAT_BACKDROPS: tuple[tuple[int, int, int], ...] = ((239, 234, 226), (11, 20, 26))


def phash_hex(image: Image.Image) -> str:
    """64-bit perceptual hash as 16 lowercase hex characters."""
    return str(imagehash.phash(flatten(image), hash_size=8))


def hamming(a: str, b: str) -> int:
    return (int(a, 16) ^ int(b, 16)).bit_count()


def _gray(image: Image.Image, max_side: int = 512) -> np.ndarray:
    rgb = flatten(image)
    rgb.thumbnail((max_side, max_side), Image.Resampling.BILINEAR)
    return np.asarray(rgb.convert("L"), dtype=np.float32)


def laplacian(gray: np.ndarray) -> np.ndarray:
    """4-neighbour Laplacian of the interior pixels: shape (h - 2, w - 2)."""
    return -4 * gray[1:-1, 1:-1] + gray[:-2, 1:-1] + gray[2:, 1:-1] + gray[1:-1, :-2] + gray[1:-1, 2:]


def laplacian_variance(gray: np.ndarray) -> float:
    if gray.shape[0] < 3 or gray.shape[1] < 3:
        return 0.0
    return float(laplacian(gray).var())


def erode(mask: np.ndarray, radius: int) -> np.ndarray:
    """Binary erosion with a (2r+1)² square via a summed-area table; outside the array counts as empty."""
    if radius <= 0:
        return mask.astype(bool)
    h, w = mask.shape
    k = 2 * radius + 1
    table = np.zeros((h + k, w + k), np.int32)
    table[1:, 1:] = np.pad(mask.astype(np.int32), radius).cumsum(0).cumsum(1)
    window = table[k:, k:] - table[:-k, k:] - table[k:, :-k] + table[:-k, :-k]
    return window == k * k


def relative_luminance(rgb: np.ndarray) -> np.ndarray:
    """WCAG relative luminance (0-1) of sRGB values in 0-255."""
    c = np.asarray(rgb, dtype=np.float32) / 255.0
    linear = np.where(c <= 0.04045, c / 12.92, ((c + 0.055) / 1.055) ** 2.4)
    return linear[..., 0] * 0.2126 + linear[..., 1] * 0.7152 + linear[..., 2] * 0.0722


def otsu_split(values: np.ndarray, bins: int = 256) -> float:
    """Otsu threshold of values in 0-1 (the cut that best separates a light and a dark group)."""
    histogram, edges = np.histogram(values, bins=bins, range=(0.0, 1.0))
    weights = histogram.astype(np.float64)
    centres = (edges[:-1] + edges[1:]) / 2
    below = np.cumsum(weights)
    total = below[-1]
    if total <= 0:
        return 0.5
    above = total - below
    sum_below = np.cumsum(weights * centres)
    mean_below = sum_below / np.maximum(below, 1e-9)
    mean_above = (sum_below[-1] - sum_below) / np.maximum(above, 1e-9)
    between = below * above * (mean_below - mean_above) ** 2
    return float(edges[int(np.argmax(between)) + 1])


def text_contrast(region: Image.Image, min_share: float = 0.03) -> float:
    """Worst WCAG contrast ratio (1-21) between the light and dark pixels of a caption region, over
    WhatsApp's light and dark chat backgrounds. 1.0 means no readable split (blank or one tone)."""
    rgba = region.convert("RGBA")
    if rgba.width < 2 or rgba.height < 2:
        return 1.0
    worst = 21.0
    for backdrop in CHAT_BACKDROPS:
        canvas = Image.new("RGBA", rgba.size, (*backdrop, 255))
        canvas.alpha_composite(rgba)
        lum = relative_luminance(np.asarray(canvas.convert("RGB"))).ravel()
        cut = otsu_split(lum)
        light, dark = lum[lum >= cut], lum[lum < cut]
        if min(light.size, dark.size) < min_share * lum.size:
            return 1.0
        worst = min(worst, (float(light.mean()) + 0.05) / (float(dark.mean()) + 0.05))
    return worst


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
