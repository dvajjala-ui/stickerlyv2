"""Independent check of every output against the WhatsApp sticker rules."""
from __future__ import annotations

import io
import statistics
from dataclasses import dataclass

from PIL import Image

from .encode import ANIMATED_LIMIT, MAX_ANIMATION_MS, MIN_FRAME_MS, STATIC_LIMIT, TRAY_LIMIT, TRAY_SIZE
from .metrics import frame_stats


class InvalidSticker(ValueError):
    pass


@dataclass(frozen=True)
class StickerCheck:
    kind: str  # static | animated
    width: int
    height: int
    frames: int
    duration_ms: int
    bytes: int
    problems: tuple[str, ...]

    @property
    def ok(self) -> bool:
        return not self.problems


def first_frame_complete(stats: list[tuple[float, float]]) -> bool:
    """Frame 0 must show the whole sticker: coverage and detail close to the animation's norm.

    Compared with the median (not the max) so zoom effects, whose middle frames are
    cropped close-ups, do not count against a complete first frame.
    """
    coverage0, detail0 = stats[0]
    if coverage0 < 0.02:
        return False
    median_coverage = statistics.median(c for c, _ in stats)
    median_detail = statistics.median(d for _, d in stats)
    return coverage0 >= 0.85 * median_coverage and detail0 >= 0.5 * median_detail


def inspect_sticker(data: bytes) -> StickerCheck:
    problems: list[str] = []
    try:
        image = Image.open(io.BytesIO(data))
        image.load()
    except (OSError, ValueError):
        return StickerCheck("static", 0, 0, 0, 0, len(data), ("undecodable",))
    if image.format != "WEBP":
        problems.append("not_webp")
    if image.size != (512, 512):
        problems.append("not_512x512")
    frames = getattr(image, "n_frames", 1)
    kind = "animated" if frames > 1 else "static"
    total = 0
    if kind == "animated":
        stats = []
        for index in range(frames):
            image.seek(index)
            image.load()  # Pillow refreshes info["duration"] when the frame is decoded
            duration = int(image.info.get("duration", 0) or 0)
            total += duration
            if duration < MIN_FRAME_MS:
                problems.append("frame_under_8ms")
            stats.append(frame_stats(image))
        if total > MAX_ANIMATION_MS:
            problems.append("longer_than_10s")
        if not first_frame_complete(stats):
            problems.append("first_frame_incomplete")
        if len(data) > ANIMATED_LIMIT:
            problems.append("over_500kb")
    else:
        if len(data) > STATIC_LIMIT:
            problems.append("over_100kb")
        if frame_stats(image)[0] < 0.02:
            problems.append("blank")
    return StickerCheck(kind, image.width, image.height, frames, total, len(data), tuple(dict.fromkeys(problems)))


def require_valid(data: bytes, kind: str) -> StickerCheck:
    check = inspect_sticker(data)
    if check.kind != kind:
        raise InvalidSticker(f"expected {kind}, got {check.kind}")
    if not check.ok:
        raise InvalidSticker(",".join(check.problems))
    return check


def inspect_tray(data: bytes) -> tuple[str, ...]:
    problems: list[str] = []
    try:
        image = Image.open(io.BytesIO(data))
        image.load()
    except (OSError, ValueError):
        return ("undecodable",)
    if image.format != "PNG":
        problems.append("not_png")
    if image.size != (TRAY_SIZE, TRAY_SIZE):
        problems.append("not_96x96")
    if len(data) > TRAY_LIMIT:
        problems.append("over_50kb")
    return tuple(problems)
