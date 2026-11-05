"""Local quality score for a finished sticker: 0-100 plus machine-readable reasons. No AI, no I/O.

Measured on the 512x512 canvas (the first frame of an animation, which validate.py requires to be complete):
- coverage: share of visible pixels and of the alpha bounding box; `blank` = nothing visible, or an empty
  card (flat colour filling its bounding box);
- sharpness: Laplacian variance inside the subject, absolute and relative to the subject's contrast; a blur
  verdict needs both to be low. The caption, the card border and the cut-out outline are masked out,
  because the renderer draws those itself and they are always crisp. Flat-colour subjects have no texture
  to judge and get no sharpness verdict;
- caption (when its box is known): inside the canvas, and the contrast between its light and dark pixels
  over WhatsApp's light and dark chat backgrounds (WCAG ratio; 3:1 is the large-text minimum);
- near-duplicate of another sticker (looked up by the caller with pipeline.fingerprints);
- animation: frame count, total duration and the median time of the middle frames (first/last are holds).

score = 100 - penalties. Hard problems also cap the score (HARD_CAP, BLANK_CAP for blank), so no vision
judge can lift them over the gate. Thresholds were calibrated on synthetic renders: sharp photos measure
200-700, cartoons and memes 1,000-2,000, a 2 px Gaussian blur ~9, a 3x upscale of a 150 px picture ~16.
"""
from __future__ import annotations

import io
import math
import statistics
from collections.abc import Sequence
from dataclasses import dataclass, field, replace
from typing import Any

import numpy as np
from PIL import Image

from .metrics import erode, laplacian, phash_hex, text_contrast

Box4 = tuple[int, int, int, int]

ALPHA_VISIBLE = 16  # alpha above this is visible (bounding box, coverage)
ALPHA_SOLID = 250  # alpha from here up is the sticker body (sharpness mask)
EDGE_MASK_PX = 10  # erode the body this far: removes the 6 px card border and the 8 px cut-out outline
CAPTION_PAD_PX = 8  # also mask this far around the caption box (stroke + anti-aliasing)
MIN_MEASURED = 0.01  # a body smaller than this share of the canvas is not measured for sharpness/detail

MIN_VISIBLE = 0.02  # less visible than this is blank (validate.py rejects that for static stickers too)
MIN_OPAQUE = 0.08  # mostly transparent below this share of visible pixels
TINY_SUBJECT = 0.12  # alpha bounding box below 12% of the canvas
SMALL_SUBJECT = 0.30  # graded small_subject penalty between 30% and 12%
# Luminance spread (0-255) inside the subject below which it is flat colour (prescreen's MIN_CONTRAST).
# Flat colour has no texture to be sharp or blurry, so it gets no sharpness verdict; it is only blank when
# it also fills its bounding box (an empty card). A flat cut-out shape (a plain heart) is a fine sticker.
FLAT_DETAIL = 6.0
BOXY_FILL = 0.85  # visible share of the bounding box from which the subject is a card, not a shape
# Blur needs both measures low: the Laplacian variance itself, and the variance relative to the subject's
# contrast (variance / spread²). Dim but sharp photos have little absolute energy; a few hard edges between
# large flat areas have little relative energy. Neither is blurry.
BLURRY = 12.0  # Laplacian variance inside the subject
SOFT = 60.0  # graded soft penalty between 60 and 12 (log scale)
BLURRY_REL = 0.008  # variance / spread²: sharp photos 0.12-0.4, cartoons ~0.6, a 2 px blur ~0.006
SOFT_REL = 0.04
MIN_CAPTION_CONTRAST = 3.0
CAPTION_EDGE_TOLERANCE = 1  # px a caption box may poke past the canvas before it counts as clipped
MIN_ANIMATION_MS = 300  # shorter loops flicker
CHOPPY_FRAME_MS = 250  # median time of the middle frames above this (< 4 fps) looks choppy
FEW_FRAMES = 2

HARD_CAP = 35
BLANK_CAP = 5
MILD_MAX = 20.0
JUDGE_WEIGHT = 0.5
PENALTIES: dict[str, float] = {
    "blank": 100.0, "mostly_transparent": 50.0, "tiny_subject": 50.0, "near_duplicate": 50.0, "blurry": 45.0,
    "caption_clipped": 45.0, "caption_low_contrast": 45.0, "animation_too_short": 45.0,
    "animation_few_frames": 15.0, "animation_choppy": 15.0,
}
HARD = frozenset({"blank", "mostly_transparent", "tiny_subject", "near_duplicate", "caption_clipped",
                  "caption_low_contrast", "animation_too_short"})


@dataclass(frozen=True)
class QualityReport:
    score: int  # 0-100: compared with settings.quality_gate.min_score
    reasons: tuple[str, ...]  # worst first
    signals: dict[str, Any] = field(default_factory=dict)  # JSON-safe measurements (stickers.quality_signals)
    heuristic: int = 100  # local score before caps and before any judge
    cap: int = 100  # ceiling set by hard reasons
    render_phash: str | None = None

    @property
    def capped(self) -> bool:
        return self.cap < 100


def decode_frames(data: bytes) -> tuple[Image.Image, list[int]]:
    """First frame as RGBA and the per-frame durations in ms ([] for a static sticker)."""
    image = Image.open(io.BytesIO(data))
    image.load()
    durations: list[int] = []
    frames = getattr(image, "n_frames", 1)
    if frames > 1:
        for index in range(frames):
            image.seek(index)
            image.load()  # Pillow refreshes info["duration"] when the frame is decoded
            durations.append(int(image.info.get("duration", 0) or 0))
        image.seek(0)
        image.load()
    return image.convert("RGBA"), durations


def visible_box(image: Image.Image) -> Box4 | None:
    alpha = np.asarray(image.convert("RGBA").getchannel("A")) > ALPHA_VISIBLE
    ys, xs = np.nonzero(alpha)
    if not xs.size:
        return None
    return int(xs.min()), int(ys.min()), int(xs.max()) + 1, int(ys.max()) + 1


def render_phash(image: Image.Image) -> str:
    """pHash of the visible area only: unrelated stickers share margins and card borders, which would
    otherwise pull their hashes together."""
    rgba = image.convert("RGBA")
    box = visible_box(rgba)
    return phash_hex(rgba.crop(box) if box else rgba)


def _graded(value: float, good: float, bad: float, log: bool = False) -> float:
    """0 at `good`, MILD_MAX at `bad` (value between them)."""
    if log:
        value, good, bad = math.log(max(value, 1e-6)), math.log(good), math.log(bad)
    return MILD_MAX * min(1.0, max(0.0, (good - value) / (good - bad)))


def _blur(sharpness: float, relative: float) -> tuple[str, float] | None:
    """The milder of the absolute and the relative verdict: (reason, penalty) or None when sharp enough."""
    levels: list[tuple[int, float]] = []
    for value, blurry, soft in ((sharpness, BLURRY, SOFT), (relative, BLURRY_REL, SOFT_REL)):
        if value < blurry:
            levels.append((2, MILD_MAX))
        elif value < soft:
            levels.append((1, _graded(value, soft, blurry, log=True)))
        else:
            levels.append((0, 0.0))
    severity = min(level for level, _ in levels)
    if severity == 2:
        return "blurry", PENALTIES["blurry"]
    if severity == 1:
        return "soft", min(grade for _, grade in levels)
    return None


def _finish(penalties: dict[str, float], signals: dict[str, Any], phash: str | None) -> QualityReport:
    reasons = tuple(sorted(penalties, key=lambda r: -penalties[r]))  # stable: equal penalties keep their order
    heuristic = int(max(0, min(100, round(100 - sum(penalties.values())))))
    cap = BLANK_CAP if "blank" in penalties else HARD_CAP if HARD & set(penalties) else 100
    signals["penalties"] = {reason: round(penalties[reason], 1) for reason in reasons}
    return QualityReport(min(heuristic, cap), reasons, signals, heuristic, cap, phash)


def score_image(image: Image.Image, *, caption_box: Box4 | None = None, durations: Sequence[int] = (),
                duplicate: tuple[str, int] | None = None, phash: str | None = None) -> QualityReport:
    """Score one finished sticker canvas (expects 512x512 RGBA; thresholds are calibrated for that size)."""
    rgba = image.convert("RGBA")
    width, height = rgba.size
    alpha = np.asarray(rgba.getchannel("A"))
    gray = np.asarray(rgba.convert("L"), dtype=np.float32)
    penalties: dict[str, float] = {}
    signals: dict[str, Any] = {}

    visible = float((alpha > ALPHA_VISIBLE).mean())
    box = visible_box(rgba)
    bbox_share = (box[2] - box[0]) * (box[3] - box[1]) / float(width * height) if box else 0.0
    fill = visible / bbox_share if bbox_share else 0.0
    body = erode(alpha >= ALPHA_SOLID, EDGE_MASK_PX)
    if caption_box is not None:
        x0, y0, x1, y1 = caption_box
        body[max(0, y0 - CAPTION_PAD_PX):max(0, y1 + CAPTION_PAD_PX),
             max(0, x0 - CAPTION_PAD_PX):max(0, x1 + CAPTION_PAD_PX)] = False
    measured = float(body.mean())
    sharpness = detail = None
    if measured >= MIN_MEASURED:
        sharpness = float(laplacian(gray)[body[1:-1, 1:-1]].var())
        detail = float(gray[body].std())
    flat = detail is not None and detail < FLAT_DETAIL
    relative = sharpness / detail ** 2 if sharpness is not None and detail else None
    signals.update(visible=round(visible, 4), bbox=round(bbox_share, 4), fill=round(fill, 3),
                   measured=round(measured, 4), sharpness=None if sharpness is None else round(sharpness, 1),
                   sharpness_rel=None if relative is None else round(relative, 4),
                   detail=None if detail is None else round(detail, 1), flat=flat)

    if visible < MIN_VISIBLE or (flat and fill >= BOXY_FILL):
        penalties["blank"] = PENALTIES["blank"]
    else:
        if visible < MIN_OPAQUE:
            penalties["mostly_transparent"] = PENALTIES["mostly_transparent"]
        if bbox_share < TINY_SUBJECT:
            penalties["tiny_subject"] = PENALTIES["tiny_subject"]
        elif bbox_share < SMALL_SUBJECT:
            penalties["small_subject"] = _graded(bbox_share, SMALL_SUBJECT, TINY_SUBJECT)
        blur = _blur(sharpness, relative) if sharpness is not None and relative is not None and not flat else None
        if blur is not None:
            penalties[blur[0]] = blur[1]

    if caption_box is not None:
        x0, y0, x1, y1 = (int(v) for v in caption_box)
        signals["caption_box"] = [x0, y0, x1, y1]
        tolerance = CAPTION_EDGE_TOLERANCE
        if x1 <= x0 or y1 <= y0 or x0 < -tolerance or y0 < -tolerance or x1 > width + tolerance \
                or y1 > height + tolerance:
            penalties["caption_clipped"] = PENALTIES["caption_clipped"]
        inside = (max(0, x0), max(0, y0), min(width, x1), min(height, y1))
        contrast = text_contrast(rgba.crop(inside)) if inside[2] > inside[0] and inside[3] > inside[1] else 1.0
        signals["caption_contrast"] = round(contrast, 2)
        if contrast < MIN_CAPTION_CONTRAST:
            penalties["caption_low_contrast"] = PENALTIES["caption_low_contrast"]

    if duplicate is not None:
        signals["duplicate_of"], signals["duplicate_distance"] = duplicate[0], int(duplicate[1])
        penalties["near_duplicate"] = PENALTIES["near_duplicate"]

    if len(durations) > 1:
        total = int(sum(durations))
        middle = durations[1:-1] if len(durations) >= 3 else durations  # first/last frames are often holds
        median = statistics.median(int(d) for d in middle)
        signals.update(frames=len(durations), duration_ms=total, median_frame_ms=median)
        if total < MIN_ANIMATION_MS:
            penalties["animation_too_short"] = PENALTIES["animation_too_short"]
        if len(durations) <= FEW_FRAMES:
            penalties["animation_few_frames"] = PENALTIES["animation_few_frames"]
        if median > CHOPPY_FRAME_MS:
            penalties["animation_choppy"] = PENALTIES["animation_choppy"]

    return _finish(penalties, signals, phash or render_phash(rgba))


def score_sticker(data: bytes, *, caption_box: Box4 | None = None,
                  duplicate: tuple[str, int] | None = None) -> QualityReport:
    """Decode an encoded sticker (WebP) and score its first frame and timing."""
    image, durations = decode_frames(data)
    return score_image(image, caption_box=caption_box, durations=durations, duplicate=duplicate)


def with_judge(report: QualityReport, judge_score: int, issues: Sequence[str] = ()) -> QualityReport:
    """Blend a vision judge's 0-10 score into the local score. Hard caps still apply."""
    judge = max(0, min(10, int(judge_score)))
    blended = round((1 - JUDGE_WEIGHT) * report.heuristic + JUDGE_WEIGHT * judge * 10)
    found = tuple(dict.fromkeys(f"judge:{issue}" for issue in issues if issue))
    if not found and judge <= 4:
        found = ("judge:low_score",)
    signals = {**report.signals, "judge": {"score": judge, "issues": list(issues)}, "local_score": report.score}
    return replace(report, score=int(max(0, min(report.cap, blended))),
                   reasons=tuple(dict.fromkeys((*report.reasons, *found))), signals=signals)
