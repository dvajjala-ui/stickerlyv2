"""Safe decoding of collected media (Pillow for images; video posters via imaging.clip)."""
from __future__ import annotations

import io
import warnings
from dataclasses import dataclass

from PIL import Image, ImageOps

MAX_PIXELS = 40_000_000
MAX_FRAMES = 2000

IMAGE_MIMES = frozenset({"image/png", "image/jpeg", "image/webp", "image/gif"})
VIDEO_MIMES = frozenset({"video/mp4", "video/webm"})
EXTENSIONS = {"image/png": "png", "image/jpeg": "jpg", "image/webp": "webp", "image/gif": "gif",
              "video/mp4": "mp4", "video/webm": "webm"}


class DecodeError(ValueError):
    pass


def sniff_mime(data: bytes) -> str | None:
    """Identify the container from magic bytes (never trust a file extension)."""
    if data.startswith(b"\x89PNG\r\n\x1a\n"):
        return "image/png"
    if data[:3] == b"\xff\xd8\xff":
        return "image/jpeg"
    if data[:6] in (b"GIF87a", b"GIF89a"):
        return "image/gif"
    if data[:4] == b"RIFF" and data[8:12] == b"WEBP":
        return "image/webp"
    if data[4:8] == b"ftyp":
        return "video/mp4"
    if data[:4] == b"\x1a\x45\xdf\xa3":
        return "video/webm"
    return None


@dataclass
class Decoded:
    image: Image.Image  # first frame, RGBA, EXIF orientation applied
    width: int
    height: int
    frames: int
    duration_ms: int
    mime: str

    @property
    def animated(self) -> bool:
        return self.frames > 1


def open_image(data: bytes) -> Image.Image:
    """Open with decompression-bomb protection; raises DecodeError."""
    try:
        with warnings.catch_warnings():
            warnings.simplefilter("error", Image.DecompressionBombWarning)
            image = Image.open(io.BytesIO(data))
            if image.width * image.height > MAX_PIXELS:
                raise DecodeError("too_many_pixels")
            image.load()
            return image
    except DecodeError:
        raise
    except (OSError, ValueError, SyntaxError, Image.DecompressionBombWarning, Image.DecompressionBombError) as error:
        raise DecodeError(f"undecodable:{type(error).__name__}") from None


def first_frame(image: Image.Image) -> Image.Image:
    image.seek(0)
    return ImageOps.exif_transpose(image).convert("RGBA")


def decode_image(data: bytes) -> Decoded:
    mime = sniff_mime(data)
    if mime not in IMAGE_MIMES:
        raise DecodeError("unsupported_format")
    image = open_image(data)
    frames = min(getattr(image, "n_frames", 1), MAX_FRAMES)
    duration = 0
    if frames > 1:
        for index in range(frames):
            image.seek(index)
            duration += int(image.info.get("duration", 100) or 100)
    frame = first_frame(image)
    return Decoded(frame, frame.width, frame.height, frames, duration if frames > 1 else 0, mime)


def flatten(image: Image.Image, background: tuple[int, int, int] = (255, 255, 255)) -> Image.Image:
    rgba = image.convert("RGBA")
    canvas = Image.new("RGBA", rgba.size, (*background, 255))
    canvas.alpha_composite(rgba)
    return canvas.convert("RGB")


def vision_jpeg(image: Image.Image, max_side: int = 1024, quality: int = 85,
                background: tuple[int, int, int] = (255, 255, 255)) -> bytes:
    """Downscale to max_side, flatten, re-encode. Re-encoding drops EXIF and other metadata."""
    rgb = flatten(image, background)
    rgb.thumbnail((max_side, max_side), Image.Resampling.LANCZOS)
    buffer = io.BytesIO()
    rgb.save(buffer, format="JPEG", quality=quality, optimize=True)
    return buffer.getvalue()
