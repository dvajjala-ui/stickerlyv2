"""Sticker compositions on the 512x512 canvas: direct, captioned and cut-out."""
from __future__ import annotations

from dataclasses import dataclass

from PIL import Image

from ..analysis import Crop
from .clip import to_canvas
from .layout import CANVAS, INNER, MARGIN, Box, blank, crop_to, is_cutout, map_crop, place, subject_visual, trim_borders
from .text import CaptionLayout, draw_caption, ink_box, layout_caption

CAPTION_MAX_HEIGHT = 168
GAP = 6
OVERLAY_MAX_OVERLAP = 0.12  # share of the subject a caption may cover before we stack instead
FULL = Box(MARGIN, MARGIN, CANVAS - MARGIN, CANVAS - MARGIN)


def _caption_box(layout: CaptionLayout | None, top: int | None) -> tuple[int, int, int, int] | None:
    return ink_box(layout, top) if layout is not None and top is not None else None


@dataclass
class Composed:
    image: Image.Image
    plain: Image.Image | None = None  # same picture without caption (typewriter effect)
    layout: CaptionLayout | None = None
    caption_top: int | None = None
    position: str | None = None  # top | bottom | stacked-top | stacked-bottom

    @property
    def caption_box(self) -> tuple[int, int, int, int] | None:
        """Where the caption was inked on the canvas (for the quality gate), None without a caption."""
        return _caption_box(self.layout, self.caption_top)


@dataclass
class ComposedClip:
    frames: list[Image.Image]
    layout: CaptionLayout | None = None
    caption_top: int | None = None

    @property
    def caption_box(self) -> tuple[int, int, int, int] | None:
        return _caption_box(self.layout, self.caption_top)


def limit_aspect(image: Image.Image, crop: Crop, max_aspect: float = 1.8) -> tuple[Image.Image, Crop]:
    """Trim an extreme panorama/portrait around the subject; returns the crop re-expressed in it."""
    w, h = image.size
    if max(w, h) / max(1, min(w, h)) <= max_aspect:
        return image, crop
    cx, cy = (crop.x + crop.w / 2) * w, (crop.y + crop.h / 2) * h
    if w > h:
        new_w = h * max_aspect
        x0 = min(max(0.0, cx - new_w / 2), w - new_w)
        box = (x0, 0.0, x0 + new_w, float(h))
    else:
        new_h = w * max_aspect
        y0 = min(max(0.0, cy - new_h / 2), h - new_h)
        box = (0.0, y0, float(w), y0 + new_h)
    bw, bh = box[2] - box[0], box[3] - box[1]
    moved = Crop.parse({"x": (crop.x * w - box[0]) / bw, "y": (crop.y * h - box[1]) / bh,
                        "w": crop.w * w / bw, "h": crop.h * h / bh})
    return image.crop(tuple(round(v) for v in box)), moved


TEXT_SAFE_CROP = 0.9  # a crop on a picture with text must keep 90% of each side, or none is used


def direct(image: Image.Image, crop: Crop, has_text: bool = False) -> Composed:
    """Existing meme as found: crop to subject+text, fit on the transparent canvas, no overlay.

    A model crop can clip words, so pictures with text only take near-full crops; flat
    borders (letterbox bars, white meme frames) are trimmed instead.
    """
    if has_text and (crop.w < TEXT_SAFE_CROP or crop.h < TEXT_SAFE_CROP):
        crop = Crop()
    source = image if crop.is_full else crop_to(image, crop, pad=0.02)
    if not is_cutout(source):
        source = trim_borders(source)
    canvas = blank()
    place(canvas, subject_visual(source, INNER, INNER), FULL)
    return Composed(canvas)


def _centered(visual: Image.Image, area: Box) -> Box:
    x = area.x0 + (area.width - visual.width) // 2
    y = area.y0 + (area.height - visual.height) // 2
    return Box(x, y, x + visual.width, y + visual.height)


def captioned(image: Image.Image, crop: Crop, text: str) -> Composed | None:
    """Caption below a wide picture when there is room; otherwise overlay it on the band (top or
    bottom) that covers less of the subject, or shrink the picture and stack when both would."""
    layout = layout_caption(text, INNER, CAPTION_MAX_HEIGHT)
    if layout is None:
        return None
    source, subject_crop = limit_aspect(image, crop)
    visual = subject_visual(source, INNER, INNER)
    canvas = blank()
    if visual.height + GAP + layout.height <= INNER:
        y0 = MARGIN + (INNER - visual.height - GAP - layout.height) // 2
        placed = place(canvas, visual, Box(MARGIN, y0, CANVAS - MARGIN, y0 + visual.height))
        plain = canvas.copy()
        draw_caption(canvas, layout, placed.y1 + GAP)
        return Composed(canvas, plain, layout, placed.y1 + GAP, "below")
    subject = map_crop(subject_crop, _centered(visual, FULL))
    bands = {"top": Box(MARGIN, MARGIN, CANVAS - MARGIN, MARGIN + layout.height),
             "bottom": Box(MARGIN, CANVAS - MARGIN - layout.height, CANVAS - MARGIN, CANVAS - MARGIN)}
    overlap = {name: band.intersection(subject) / max(1, subject.area) for name, band in bands.items()}
    position = "top" if overlap["top"] < overlap["bottom"] else "bottom"
    if overlap[position] <= OVERLAY_MAX_OVERLAP:
        place(canvas, visual, FULL)
        top = bands[position].y0
    else:
        area_h = INNER - layout.height - GAP
        visual = subject_visual(source, INNER, area_h)
        if position == "top":
            placed = place(canvas, visual, Box(MARGIN, CANVAS - MARGIN - area_h, CANVAS - MARGIN, CANVAS - MARGIN))
            top = max(MARGIN, placed.y0 - GAP - layout.height)
        else:
            placed = place(canvas, visual, Box(MARGIN, MARGIN, CANVAS - MARGIN, MARGIN + area_h))
            top = min(CANVAS - MARGIN - layout.height, placed.y1 + GAP)
        position = f"stacked-{position}"
    plain = canvas.copy()
    draw_caption(canvas, layout, top)
    return Composed(canvas, plain, layout, top, position)


def cutout(subject: Image.Image, text: str | None) -> Composed | None:
    """Cut-out subject with a white 8 px outline (via subject_visual), caption underneath when given."""
    canvas = blank()
    if not text:
        place(canvas, subject_visual(subject, INNER, INNER), FULL)
        return Composed(canvas)
    layout = layout_caption(text, INNER, CAPTION_MAX_HEIGHT)
    if layout is None:
        return None
    area_h = INNER - layout.height - GAP
    placed = place(canvas, subject_visual(subject, INNER, area_h), Box(MARGIN, MARGIN, CANVAS - MARGIN,
                                                                        MARGIN + area_h))
    top = min(CANVAS - MARGIN - layout.height, placed.y1 + GAP)
    plain = canvas.copy()
    draw_caption(canvas, layout, top)
    return Composed(canvas, plain, layout, top, "stacked-bottom")


def clip_composed(frames: list[Image.Image], text: str | None) -> ComposedClip | None:
    """Animated clip on the canvas; with a caption, the clip shrinks and the caption sits below."""
    if not text:
        return ComposedClip(to_canvas(frames)[0])
    layout = layout_caption(text, INNER, CAPTION_MAX_HEIGHT)
    if layout is None:
        return None
    canvases, box = to_canvas(frames, box_h=INNER - layout.height - GAP)
    top = min(CANVAS - MARGIN - layout.height, box[3] + GAP)
    for canvas in canvases:
        draw_caption(canvas, layout, top)
    return ComposedClip(canvases, layout, top)


def clip_with_caption(frames: list[Image.Image], text: str | None) -> list[Image.Image] | None:
    made = clip_composed(frames, text)
    return made.frames if made is not None else None
