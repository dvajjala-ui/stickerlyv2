"""GIF / animated WebP (Pillow) and MP4 / WebM (ffmpeg, optional) to sticker frames.

Clips are trimmed to <= 3 s and resampled to 10 fps; the start moves forward past
blank/fade-in frames so the first frame is a complete picture.
"""
from __future__ import annotations

import shutil
import subprocess
import tempfile
from pathlib import Path

from PIL import Image, ImageSequence

from .decode import EXTENSIONS, VIDEO_MIMES, open_image
from .layout import CANVAS, INNER, MARGIN, blank, contain_size, is_cutout, photo_card
from .metrics import frame_stats
from .validate import first_frame_complete

MAX_CLIP_MS = 3000
FPS = 10
MAX_DECODED_FRAMES = 400


class ClipError(ValueError):
    pass


def ffmpeg_available() -> bool:
    return shutil.which("ffmpeg") is not None


def image_frames(data: bytes) -> list[tuple[Image.Image, int]]:
    image = open_image(data)
    frames: list[tuple[Image.Image, int]] = []
    for index, frame in enumerate(ImageSequence.Iterator(image)):
        if index >= MAX_DECODED_FRAMES:
            break
        frames.append((frame.convert("RGBA"), max(20, int(frame.info.get("duration", 100) or 100))))
    return frames


def resample(frames: list[tuple[Image.Image, int]], fps: int = FPS, max_ms: int = MAX_CLIP_MS) -> list[Image.Image]:
    """Pick the frame on screen at each tick of a fixed-rate timeline."""
    total = sum(d for _, d in frames)
    out: list[Image.Image] = []
    tick = 1000 // fps
    clock, index, frame_end = 0, 0, frames[0][1]
    while clock < min(total, max_ms):
        while clock >= frame_end and index + 1 < len(frames):
            index += 1
            frame_end += frames[index][1]
        out.append(frames[index][0])
        clock += tick
    return out


def video_frames(data: bytes, mime: str, fps: int = FPS, max_ms: int = MAX_CLIP_MS) -> list[Image.Image]:
    if mime not in VIDEO_MIMES:
        raise ClipError("not a video")
    if not ffmpeg_available():
        raise ClipError("ffmpeg_unavailable")
    with tempfile.TemporaryDirectory(prefix="stickerworker-") as temp:
        source = Path(temp) / f"input.{EXTENSIONS[mime]}"
        source.write_bytes(data)
        args = ["ffmpeg", "-nostdin", "-hide_banner", "-loglevel", "error", "-y", "-protocol_whitelist", "file,pipe",
                "-i", str(source), "-t", f"{max_ms / 1000:.2f}", "-an", "-vf",
                f"fps={fps},scale={CANVAS}:{CANVAS}:force_original_aspect_ratio=decrease",
                "-frames:v", str(max_ms * fps // 1000), str(Path(temp) / "%03d.png")]
        try:
            subprocess.run(args, check=True, capture_output=True, timeout=90)
        except (OSError, subprocess.CalledProcessError, subprocess.TimeoutExpired):
            raise ClipError("ffmpeg_failed") from None
        frames = []
        for path in sorted(Path(temp).glob("*.png")):
            with Image.open(path) as frame:
                frames.append(frame.convert("RGBA"))
    if not frames:
        raise ClipError("no_frames")
    return frames


def poster(data: bytes, mime: str) -> Image.Image:
    """First frame of a clip (video via ffmpeg)."""
    if mime in VIDEO_MIMES:
        return video_frames(data, mime, fps=2, max_ms=500)[0]
    image = open_image(data)
    image.seek(0)
    return image.convert("RGBA")


def clip_frames(data: bytes, mime: str) -> list[Image.Image]:
    raw = video_frames(data, mime) if mime in VIDEO_MIMES else resample(image_frames(data))
    return complete_start(raw)


def complete_start(frames: list[Image.Image]) -> list[Image.Image]:
    """Start at the first frame that already shows the full picture (skip fades/blank intros)."""
    stats = [frame_stats(frame) for frame in frames]
    for index in range(len(frames) - 1):
        if first_frame_complete(stats[index:]):
            return frames[index:]
    return frames


def to_canvas(frames: list[Image.Image], box_h: int = INNER) -> tuple[list[Image.Image], tuple[int, int, int, int]]:
    """Fit every frame the same way (card or plain) into the canvas; returns frames and the picture box."""
    cut = is_cutout(frames[0])
    out: list[Image.Image] = []
    box = (0, 0, 0, 0)
    for frame in frames:
        if cut:
            visual = frame.resize(contain_size(frame.width, frame.height, INNER, box_h), Image.Resampling.LANCZOS)
        else:
            visual = photo_card(frame, INNER, box_h)
        canvas = blank()
        x = (CANVAS - visual.width) // 2
        y = MARGIN + (box_h - visual.height) // 2
        canvas.alpha_composite(visual, (x, y))
        out.append(canvas)
        box = (x, y, x + visual.width, y + visual.height)
    return out, box
