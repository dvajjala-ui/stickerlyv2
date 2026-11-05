"""Animated variants of a finished static sticker. Frame 0 is always the complete sticker."""
from __future__ import annotations

import hashlib
from collections.abc import Callable

from PIL import Image

from .layout import CANVAS, blank
from .text import CaptionLayout, draw_caption, visible_units

Frames = tuple[list[Image.Image], list[int]]
EFFECTS = ("pop", "shake", "zoom_punch", "typewriter")


def _transform(base: Image.Image, scale: float = 1.0, dx: int = 0, dy: int = 0, angle: float = 0.0) -> Image.Image:
    image = base
    if angle:
        image = image.rotate(angle, resample=Image.Resampling.BICUBIC, center=(CANVAS / 2, CANVAS / 2),
                             fillcolor=(0, 0, 0, 0))
    if scale != 1.0:
        size = max(2, round(CANVAS * scale))
        image = image.resize((size, size), Image.Resampling.LANCZOS)
    frame = blank()
    offset = (CANVAS - image.width) // 2 + dx, (CANVAS - image.height) // 2 + dy
    frame.paste(image, offset)  # frame is empty, so a plain (clipped) paste is exact
    return frame


def pop(base: Image.Image) -> Frames:
    scales = (0.88, 0.94, 1.04, 1.06, 1.02, 0.99)
    frames = [base.copy()] + [_transform(base, scale=s) for s in scales] + [base.copy()]
    return frames, [700] + [60] * len(scales) + [900]


def shake(base: Image.Image) -> Frames:
    moves = ((-9, -2.5), (9, 2.5), (-7, -1.8), (7, 1.8), (-4, -1.0), (4, 1.0))
    frames = [base.copy()] + [_transform(base, dx=dx, angle=a) for dx, a in moves] + [base.copy()]
    return frames, [600] + [50] * len(moves) + [900]


def zoom_punch(base: Image.Image) -> Frames:
    scales = (1.10, 1.14, 1.08, 1.0, 1.05, 1.0)
    frames = [base.copy()] + [_transform(base, scale=s) for s in scales] + [base.copy()]
    return frames, [650] + [45] * len(scales) + [900]


def typewriter(base: Image.Image, plain: Image.Image, layout: CaptionLayout, top: int, max_steps: int = 18) -> Frames:
    """Complete sticker first, then the caption types itself in over the caption-free picture."""
    units = visible_units(layout)
    step = max(1, -(-units // max_steps))
    frames, durations = [base.copy(), plain.copy()], [600, 150]
    for shown in range(step, units, step):
        frame = plain.copy()
        draw_caption(frame, layout, top, upto=shown)
        frames.append(frame)
        durations.append(70)
    frames.append(base.copy())
    durations.append(1300)
    return frames, durations


SIMPLE_EFFECTS: dict[str, Callable[[Image.Image], Frames]] = {"pop": pop, "shake": shake, "zoom_punch": zoom_punch}


def choose_effect(seed: str, has_caption: bool) -> str:
    options = (*SIMPLE_EFFECTS, "typewriter") if has_caption else tuple(SIMPLE_EFFECTS)
    digest = int(hashlib.sha256(seed.encode()).hexdigest()[:8], 16)
    return options[digest % len(options)]
