"""WebP/PNG encoders that iterate quality (then frame count) until WhatsApp limits are met."""
from __future__ import annotations

import io
from collections.abc import Sequence

from PIL import Image

from .layout import CANVAS

STATIC_LIMIT = 100_000
ANIMATED_LIMIT = 500_000
TRAY_LIMIT = 50_000
TRAY_SIZE = 96
MIN_FRAME_MS = 8
MAX_ANIMATION_MS = 10_000


class EncodeError(ValueError):
    pass


def _canvas(image: Image.Image) -> Image.Image:
    if image.size != (CANVAS, CANVAS):
        raise EncodeError("sticker canvas must be 512x512")
    return image.convert("RGBA")


def encode_static(image: Image.Image, limit: int = STATIC_LIMIT) -> bytes:
    rgba = _canvas(image)
    for quality in (92, 85, 78, 70, 60, 50, 40, 30, 20):
        buffer = io.BytesIO()
        rgba.save(buffer, format="WEBP", quality=quality, method=6, alpha_quality=90 if quality >= 60 else 70)
        if buffer.tell() <= limit:
            return buffer.getvalue()
    raise EncodeError("static sticker cannot fit 100 KB")


def _save_animation(frames: Sequence[Image.Image], durations: Sequence[int], quality: int) -> bytes:
    buffer = io.BytesIO()
    frames[0].save(buffer, format="WEBP", save_all=True, append_images=list(frames[1:]), duration=list(durations),
                   loop=0, quality=quality, method=4, alpha_quality=80, background=(0, 0, 0, 0))
    return buffer.getvalue()


def _thin(frames: list[Image.Image], durations: list[int]) -> tuple[list[Image.Image], list[int]]:
    """Drop every second middle frame (first and last are kept), folding its time into a kept one."""
    kept_frames, kept_durations = [frames[0]], [durations[0]]
    for index in range(1, len(frames) - 1):
        if (index - 1) % 2 == 0:
            kept_frames.append(frames[index])
            kept_durations.append(durations[index])
        else:
            kept_durations[-1] += durations[index]
    kept_frames.append(frames[-1])
    kept_durations.append(durations[-1])
    return kept_frames, kept_durations


def encode_animated(frames: Sequence[Image.Image], durations: Sequence[int], limit: int = ANIMATED_LIMIT) -> bytes:
    if len(frames) < 2 or len(frames) != len(durations):
        raise EncodeError("animation needs at least two frames with durations")
    current = [_canvas(frame) for frame in frames]
    timing = [max(MIN_FRAME_MS, int(d)) for d in durations]
    if sum(timing) > MAX_ANIMATION_MS:
        raise EncodeError("animation longer than 10 s")
    qualities = (80, 68, 56, 45, 35, 25)
    for _ in range(4):
        index = 0
        while index < len(qualities):
            data = _save_animation(current, timing, qualities[index])
            if len(data) <= limit:
                return data
            ratio = len(data) / limit  # far over the limit: skip quality steps that cannot fit
            index += 3 if ratio > 2.2 else 2 if ratio > 1.5 else 1
        if len(current) <= 3:
            break
        current, timing = _thin(current, timing)
    raise EncodeError("animated sticker cannot fit 500 KB")


def encode_tray(image: Image.Image) -> bytes:
    """96x96 PNG tray icon from a sticker canvas, under 50 KB."""
    rgba = image.convert("RGBA")
    bbox = rgba.getchannel("A").getbbox()
    if bbox:
        rgba = rgba.crop(bbox)
    rgba.thumbnail((TRAY_SIZE, TRAY_SIZE), Image.Resampling.LANCZOS)
    tray = Image.new("RGBA", (TRAY_SIZE, TRAY_SIZE), (0, 0, 0, 0))
    tray.alpha_composite(rgba, ((TRAY_SIZE - rgba.width) // 2, (TRAY_SIZE - rgba.height) // 2))
    for candidate in (tray, tray.quantize(colors=128, method=Image.Quantize.FASTOCTREE)):
        buffer = io.BytesIO()
        candidate.save(buffer, format="PNG", optimize=True)
        if buffer.tell() <= TRAY_LIMIT:
            return buffer.getvalue()
    raise EncodeError("tray icon cannot fit 50 KB")
