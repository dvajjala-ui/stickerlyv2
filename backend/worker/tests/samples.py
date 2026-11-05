"""Synthetic sample media (no real people, no third-party images) for tests and the offline demo."""
from __future__ import annotations

import io
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFont

FONT = Path(__file__).resolve().parents[1] / "stickerworker" / "fonts" / "Anton-Regular.ttf"


def photo(width: int = 640, height: int = 480, seed: int = 1, hue: int = 0) -> Image.Image:
    """Noisy 'photo' with seed-specific large shapes (distinct pHash) and a simple face."""
    rng = np.random.default_rng(seed)
    base = np.zeros((height, width, 3), np.float32)
    base[..., 0] = np.linspace(30 + hue, 200, width)[None, :]
    base[..., 1] = np.linspace(70, 170 - hue / 2, height)[:, None]
    base[..., 2] = 110 + hue / 3
    image = Image.fromarray(np.clip(base, 0, 255).astype(np.uint8)).convert("RGB")
    draw = ImageDraw.Draw(image)
    for _ in range(7):  # low-frequency structure that differs per seed
        x0, y0 = int(rng.integers(0, width)), int(rng.integers(0, height))
        w, h = int(rng.integers(width // 5, width // 2)), int(rng.integers(height // 5, height // 2))
        shade = tuple(int(v) for v in rng.integers(0, 256, 3))
        (draw.ellipse if rng.random() < 0.5 else draw.rectangle)((x0, y0, x0 + w, y0 + h), fill=shade)
    noisy = np.asarray(image, np.float32) + rng.normal(0, 14, (height, width, 3))
    image = Image.fromarray(np.clip(noisy, 0, 255).astype(np.uint8))
    draw = ImageDraw.Draw(image)
    cx = int(width * rng.uniform(0.35, 0.65))
    cy, r = height // 2, min(width, height) // 4
    draw.ellipse((cx - r, cy - r, cx + r, cy + int(r * 1.2)), fill=(232, 182, 140))
    for dx in (-r // 3, r // 3):
        draw.ellipse((cx + dx - 12, cy - r // 4 - 12, cx + dx + 12, cy - r // 4 + 12), fill="black")
    draw.arc((cx - r // 2, cy, cx + r // 2, cy + r // 2 + seed % 3 * 6), 20, 160, fill="black", width=6)
    return image


def meme(width: int = 600, height: int = 600, seed: int = 3) -> Image.Image:
    image = photo(width, height, seed=seed, hue=40)
    draw = ImageDraw.Draw(image)
    font = ImageFont.truetype(str(FONT), 54)
    for text, y in (("WHEN THE WIFI DROPS", 20), ("RIGHT BEFORE THE MATCH", height - 90)):
        draw.text((width // 2, y), text, font=font, fill="white", stroke_width=5, stroke_fill="black", anchor="ma")
    return image


def cartoon(size: int = 520) -> Image.Image:
    image = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    draw = ImageDraw.Draw(image)
    draw.ellipse((80, 120, size - 80, size - 40), fill=(250, 170, 60, 255), outline="black", width=8)
    draw.polygon([(120, 170), (170, 40), (220, 150)], fill=(250, 170, 60, 255), outline="black")
    draw.polygon([(size - 120, 170), (size - 170, 40), (size - 220, 150)], fill=(250, 170, 60, 255), outline="black")
    for x in (200, size - 200):
        draw.ellipse((x - 22, 250, x + 22, 300), fill="black")
    draw.arc((200, 300, size - 200, 400), 10, 170, fill="black", width=8)
    return image


def gif(frames: int = 16, size: int = 360) -> bytes:
    background = photo(size, size, seed=7, hue=10).convert("RGB")
    palette = background.quantize(colors=128)
    images = []
    for index in range(frames):
        frame = background.copy()
        draw = ImageDraw.Draw(frame)
        x = 40 + index * (size - 80) // frames
        draw.ellipse((x, size - 120, x + 70, size - 50), fill=(30, 30, 30))
        images.append(frame.quantize(palette=palette, dither=Image.Dither.NONE))
    buffer = io.BytesIO()
    images[0].save(buffer, format="GIF", save_all=True, append_images=images[1:], duration=80, loop=0)
    return buffer.getvalue()


def encode(image: Image.Image, fmt: str, **kwargs: object) -> bytes:
    buffer = io.BytesIO()
    image.convert("RGB" if fmt == "JPEG" else "RGBA").save(buffer, format=fmt, **kwargs)
    return buffer.getvalue()


def write_sample_folder(folder: Path) -> dict[str, Path]:
    """A folder that exercises every pipeline path through FakeAI file-name hints."""
    folder.mkdir(parents=True, exist_ok=True)
    files: dict[str, bytes] = {
        "office_reaction_person_1.jpg": encode(photo(seed=11), "JPEG", quality=92),
        "office_reaction_person_2.jpg": encode(photo(seed=12, hue=30), "JPEG", quality=92),
        "office_reaction_person_3.jpg": encode(photo(seed=13, hue=60), "JPEG", quality=92),
        "meme_text_funny.png": encode(meme(), "PNG"),
        "cartoon_cat.png": encode(cartoon(), "PNG"),
        "baby_smile.jpg": encode(photo(seed=21, hue=80), "JPEG", quality=90),
        "explicit_nsfw_test.jpg": encode(photo(seed=31, hue=15), "JPEG", quality=90),
        "amitabh_bachchan_meme.jpg": encode(photo(seed=41, hue=90), "JPEG", quality=90),
        "adult_nonveg_couple.jpg": encode(photo(seed=51, hue=45), "JPEG", quality=90),
        "tiny.png": encode(photo(100, 100, seed=61), "PNG"),
        "blank.png": encode(Image.new("RGB", (600, 600), (128, 128, 128)), "PNG"),
        "dancing_dog.gif": gif(),
        "office_reaction_person_1_copy.jpg": encode(photo(seed=11).resize((600, 450)), "JPEG", quality=70),
        "notes.txt": b"not an image",
    }
    paths = {}
    for name, data in files.items():
        path = folder / name
        path.write_bytes(data)
        paths[name] = path
    (folder / "meme_text_funny.png.json").write_text(
        '{"source_url": "https://example.org/meme/1", "page_url": "https://example.org/p/1", "author": "demo",'
        ' "site": "example"}', encoding="utf-8")
    return paths
