"""Placing a picture on the transparent 512x512 sticker canvas."""
from __future__ import annotations

from dataclasses import dataclass
from functools import lru_cache

import numpy as np
from PIL import Image, ImageChops, ImageDraw, ImageFilter

from ..analysis import Crop

CANVAS = 512
MARGIN = 16
INNER = CANVAS - 2 * MARGIN
CARD_BORDER = 6
CARD_RADIUS = 26
OUTLINE = 8


@dataclass(frozen=True)
class Box:
    x0: int
    y0: int
    x1: int
    y1: int

    @property
    def width(self) -> int:
        return self.x1 - self.x0

    @property
    def height(self) -> int:
        return self.y1 - self.y0

    @property
    def area(self) -> int:
        return max(0, self.width) * max(0, self.height)

    def intersection(self, other: Box) -> int:
        w = min(self.x1, other.x1) - max(self.x0, other.x0)
        h = min(self.y1, other.y1) - max(self.y0, other.y0)
        return max(0, w) * max(0, h)


def blank() -> Image.Image:
    return Image.new("RGBA", (CANVAS, CANVAS), (0, 0, 0, 0))


def is_cutout(image: Image.Image) -> bool:
    """True when the picture already has a meaningful transparent background."""
    alpha = image.convert("RGBA").getchannel("A")
    low, _ = alpha.getextrema()
    if low > 200:
        return False
    histogram = alpha.histogram()
    transparent = sum(histogram[:17])
    return transparent / max(1, image.width * image.height) > 0.03


def crop_to(image: Image.Image, crop: Crop, pad: float = 0.03, max_aspect: float | None = None) -> Image.Image:
    """Crop to a normalised box (with padding); optionally widen/heighten to cap the aspect ratio."""
    w, h = image.size
    x0 = max(0.0, crop.x - pad) * w
    y0 = max(0.0, crop.y - pad) * h
    x1 = min(1.0, crop.x + crop.w + pad) * w
    y1 = min(1.0, crop.y + crop.h + pad) * h
    if max_aspect:
        bw, bh = x1 - x0, y1 - y0
        if bw / max(bh, 1) > max_aspect:  # too wide: add height around the box centre
            grow = min(h, bw / max_aspect) - bh
            y0, y1 = max(0.0, y0 - grow / 2), min(float(h), y1 + grow / 2)
        elif bh / max(bw, 1) > max_aspect:
            grow = min(w, bh / max_aspect) - bw
            x0, x1 = max(0.0, x0 - grow / 2), min(float(w), x1 + grow / 2)
    box = (int(x0), int(y0), max(int(x0) + 1, int(round(x1))), max(int(y0) + 1, int(round(y1))))
    return image.crop(box) if box != (0, 0, w, h) else image


def trim_borders(image: Image.Image, tolerance: float = 4.0, max_fraction: float = 0.2) -> Image.Image:
    """Remove flat single-colour bars (letterbox, white meme borders) from the edges."""
    gray = np.asarray(image.convert("RGBA").convert("L"), dtype=np.float32)
    h, w = gray.shape
    rows, cols = gray.std(axis=1) > tolerance, gray.std(axis=0) > tolerance

    def first(flags: np.ndarray, limit: int) -> int:
        hits = np.flatnonzero(flags[:limit])
        return int(hits[0]) if hits.size else 0

    top, left = first(rows, int(h * max_fraction)), first(cols, int(w * max_fraction))
    bottom = h - first(rows[::-1], int(h * max_fraction))
    right = w - first(cols[::-1], int(w * max_fraction))
    if bottom - top < h * 0.5 or right - left < w * 0.5 or (top, left, bottom, right) == (0, 0, h, w):
        return image
    return image.crop((left, top, right, bottom))


def contain_size(width: int, height: int, box_w: int, box_h: int) -> tuple[int, int]:
    scale = min(box_w / width, box_h / height)
    return max(1, round(width * scale)), max(1, round(height * scale))


@lru_cache(maxsize=64)
def _rounded_mask(size: tuple[int, int], radius: int, scale: int = 4) -> Image.Image:
    """Anti-aliased rounded-rectangle mask (cached: clip frames share one size). Do not mutate."""
    big = Image.new("L", (size[0] * scale, size[1] * scale), 0)
    ImageDraw.Draw(big).rounded_rectangle((0, 0, big.width - 1, big.height - 1), radius=radius * scale, fill=255)
    return big.resize(size, Image.Resampling.LANCZOS)


def photo_card(image: Image.Image, box_w: int, box_h: int) -> Image.Image:
    """Rectangular picture with rounded corners and a white border (reads on light and dark chats)."""
    inner_w, inner_h = contain_size(image.width, image.height, box_w - 2 * CARD_BORDER, box_h - 2 * CARD_BORDER)
    photo = image.convert("RGBA").resize((inner_w, inner_h), Image.Resampling.LANCZOS)
    radius = max(6, min(CARD_RADIUS, min(inner_w, inner_h) // 5))
    photo.putalpha(ImageChops.multiply(photo.getchannel("A"), _rounded_mask(photo.size, radius - 2)))
    card = Image.new("RGBA", (inner_w + 2 * CARD_BORDER, inner_h + 2 * CARD_BORDER), (255, 255, 255, 0))
    white = Image.new("RGBA", card.size, (255, 255, 255, 255))
    card.paste(white, (0, 0), _rounded_mask(card.size, radius + CARD_BORDER))
    card.alpha_composite(photo, (CARD_BORDER, CARD_BORDER))
    return card


def outlined(image: Image.Image, width: int = OUTLINE) -> Image.Image:
    """Transparent subject with a white outline of `width` px around its alpha shape."""
    rgba = image.convert("RGBA")
    bbox = rgba.getchannel("A").getbbox()
    if bbox:
        rgba = rgba.crop(bbox)
    pad = width + 2
    padded = Image.new("RGBA", (rgba.width + 2 * pad, rgba.height + 2 * pad), (0, 0, 0, 0))
    padded.alpha_composite(rgba, (pad, pad))
    alpha = padded.getchannel("A").point(lambda v: 255 if v > 40 else 0)
    for _ in range(width):
        alpha = alpha.filter(ImageFilter.MaxFilter(3))
    silhouette = Image.new("RGBA", padded.size, (255, 255, 255, 255))
    silhouette.putalpha(alpha.filter(ImageFilter.GaussianBlur(1)))
    silhouette.alpha_composite(padded)
    return silhouette


def subject_visual(image: Image.Image, box_w: int, box_h: int) -> Image.Image:
    """Card for rectangular pictures, outline for cut-outs; fitted inside box_w x box_h."""
    if is_cutout(image):
        visual = outlined(image)
        size = contain_size(visual.width, visual.height, box_w, box_h)
        return visual.resize(size, Image.Resampling.LANCZOS)
    return photo_card(image, box_w, box_h)


def place(canvas: Image.Image, visual: Image.Image, area: Box) -> Box:
    """Centre `visual` inside `area`; returns where it landed."""
    x = area.x0 + (area.width - visual.width) // 2
    y = area.y0 + (area.height - visual.height) // 2
    canvas.alpha_composite(visual, (x, y))
    return Box(x, y, x + visual.width, y + visual.height)


def map_crop(crop: Crop, placed: Box) -> Box:
    """Where a normalised source region lands on the canvas, given the placed visual."""
    return Box(int(placed.x0 + crop.x * placed.width), int(placed.y0 + crop.y * placed.height),
               int(placed.x0 + (crop.x + crop.w) * placed.width), int(placed.y0 + (crop.y + crop.h) * placed.height))
