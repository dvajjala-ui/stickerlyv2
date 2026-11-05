"""Renderer + WhatsApp validator: 512x512, size limits, animation timing, first frame, Devanagari."""
from __future__ import annotations

import io

import numpy as np
import pytest
from PIL import Image, ImageDraw
from samples import cartoon, gif, photo

from stickerworker.analysis import Crop
from stickerworker.imaging import clip, compose, motion, text
from stickerworker.imaging.encode import ANIMATED_LIMIT, STATIC_LIMIT, encode_animated, encode_static, encode_tray
from stickerworker.imaging.layout import trim_borders
from stickerworker.imaging.validate import inspect_sticker, inspect_tray

SUBJECT = Crop(0.3, 0.2, 0.4, 0.6)


def check_static(image: Image.Image) -> bytes:
    assert image.size == (512, 512) and image.mode == "RGBA"
    data = encode_static(image)
    result = inspect_sticker(data)
    assert result.ok, result.problems
    assert result.kind == "static" and len(data) <= STATIC_LIMIT
    return data


def test_direct_caption_and_cutout_are_valid_static_stickers():
    check_static(compose.direct(photo(), SUBJECT).image)
    made = compose.captioned(photo(), SUBJECT, "Jab boss bole weekend pe bhi aana hai")
    assert made is not None and made.layout is not None
    check_static(made.image)
    cut = compose.cutout(cartoon(), "Main toh chala")
    assert cut is not None
    check_static(cut.image)


def test_worst_case_noise_still_fits_100kb():
    rng = np.random.default_rng(0)
    noise = Image.fromarray(rng.integers(0, 256, (900, 900, 3), dtype=np.uint8))
    check_static(compose.direct(noise, Crop()).image)


def test_caption_goes_below_a_wide_picture_and_avoids_the_subject_on_tall_ones():
    wide = compose.captioned(photo(900, 450), SUBJECT, "Kya scene hai")
    assert wide is not None and wide.position == "below"
    tall = photo(450, 800)
    low_subject = compose.captioned(tall, Crop(0.1, 0.55, 0.8, 0.4), "Kya scene hai")
    high_subject = compose.captioned(tall, Crop(0.1, 0.05, 0.8, 0.4), "Kya scene hai")
    assert low_subject is not None and low_subject.position.endswith("top")
    assert high_subject is not None and high_subject.position.endswith("bottom")


def test_caption_has_thick_black_outline_and_white_fill():
    layout = text.layout_caption("Chill maar yaar", 480, 168)
    assert layout is not None and layout.stroke >= 6
    canvas = Image.new("RGBA", (512, 512), (0, 0, 0, 0))
    text.draw_caption(canvas, layout, 20)
    pixels = np.asarray(canvas)
    opaque = pixels[pixels[..., 3] > 200][:, :3]
    assert (opaque.min(axis=1) > 240).sum() > 500  # white fill
    assert (opaque.max(axis=1) < 15).sum() > 500  # black stroke


def test_devanagari_caption_renders_with_real_glyphs():
    caption = "जब बॉस बोले आज ओवरटाइम है"
    layout = text.layout_caption(caption, 480, 168)
    assert layout is not None
    assert layout.font_path.endswith("NotoSansDevanagari-Variable.ttf")
    font = str(text.DEVANAGARI_FONT)
    assert all(text.has_glyph(font, ch) for ch in caption if not ch.isspace() and ord(ch) < 0x93E)
    canvas = Image.new("RGBA", (512, 512), (0, 0, 0, 0))
    text.draw_caption(canvas, layout, 40)
    assert np.asarray(canvas)[..., 3].astype(bool).sum() > 4000
    made = compose.captioned(photo(), SUBJECT, caption)
    assert made is not None
    check_static(made.image)


def test_emoji_and_unsupported_symbols_are_dropped():
    assert text.renderable("Haha 😂 sahi hai 🔥", text.LATIN_FONT) == "Haha sahi hai"


@pytest.mark.parametrize("effect", ["pop", "shake", "zoom_punch", "typewriter"])
def test_motion_effects_meet_animated_rules(effect):
    made = compose.captioned(photo(), SUBJECT, "Arre waah kya baat hai")
    assert made is not None and made.plain is not None and made.layout is not None
    if effect == "typewriter":
        frames, durations = motion.typewriter(made.image, made.plain, made.layout, made.caption_top)
    else:
        frames, durations = motion.SIMPLE_EFFECTS[effect](made.image)
    assert frames[0].tobytes() == made.image.tobytes()  # first frame is the complete sticker
    data = encode_animated(frames, durations)
    result = inspect_sticker(data)
    assert result.ok, result.problems
    assert result.kind == "animated" and result.frames >= 2
    assert result.duration_ms <= 10_000 and len(data) <= ANIMATED_LIMIT


def test_gif_clip_is_trimmed_resampled_and_valid():
    data = gif(frames=60)  # 60 x 80 ms = 4.8 s source
    frames = clip.clip_frames(data, "image/gif")
    assert len(frames) <= 30  # <= 3 s at 10 fps
    canvases = compose.clip_with_caption(frames, "Party time")
    assert canvases is not None and all(c.size == (512, 512) for c in canvases)
    encoded = encode_animated(canvases, [100] * len(canvases))
    result = inspect_sticker(encoded)
    assert result.ok, result.problems
    assert result.duration_ms <= 3000


@pytest.mark.skipif(not clip.ffmpeg_available(), reason="ffmpeg not installed")
def test_video_clip_via_ffmpeg(tmp_path):
    import subprocess

    source = tmp_path / "clip.mp4"
    subprocess.run(["ffmpeg", "-nostdin", "-loglevel", "error", "-f", "lavfi", "-i",
                    "testsrc=duration=4:size=320x240:rate=25", "-pix_fmt", "yuv420p", str(source)],
                   check=True, timeout=120)
    frames = clip.clip_frames(source.read_bytes(), "video/mp4")
    assert 2 <= len(frames) <= 30  # trimmed to 3 s at 10 fps
    canvases = compose.clip_with_caption(frames, None)
    assert canvases is not None
    result = inspect_sticker(encode_animated(canvases, [100] * len(canvases)))
    assert result.ok, result.problems
    assert clip.poster(source.read_bytes(), "video/mp4").size[0] <= 512


def test_animation_quality_and_frame_loop_meets_a_tight_limit():
    base = compose.direct(photo(seed=5), Crop()).image
    frames, durations = motion.shake(base)
    roomy = encode_animated(frames, durations)
    tight = encode_animated(frames, durations, limit=len(roomy) // 3)
    assert len(tight) <= len(roomy) // 3
    result = inspect_sticker(tight)
    assert result.ok, result.problems
    assert result.duration_ms == sum(durations)  # thinning folds time, never drops it


def _webp(image: Image.Image, **kwargs) -> bytes:
    buffer = io.BytesIO()
    image.save(buffer, format="WEBP", **kwargs)
    return buffer.getvalue()


def test_validator_rejects_rule_breakers():
    small = Image.new("RGBA", (256, 256), (255, 0, 0, 255))
    assert "not_512x512" in inspect_sticker(_webp(small)).problems
    rng = np.random.default_rng(2)
    noisy = Image.fromarray(rng.integers(0, 256, (512, 512, 3), dtype=np.uint8)).convert("RGBA")
    assert "over_100kb" in inspect_sticker(_webp(noisy, lossless=True)).problems
    png = io.BytesIO()
    small.resize((512, 512)).save(png, format="PNG")
    assert "not_webp" in inspect_sticker(png.getvalue()).problems
    full = compose.direct(photo(), SUBJECT).image
    empty = Image.new("RGBA", (512, 512), (0, 0, 0, 0))
    late = _webp(empty, save_all=True, append_images=[full, full.rotate(5)], duration=[100, 100, 100], loop=0)
    assert "first_frame_incomplete" in inspect_sticker(late).problems
    long = _webp(full, save_all=True, append_images=[full.rotate(3), full], duration=[4000, 4000, 4000], loop=0)
    assert "longer_than_10s" in inspect_sticker(long).problems


def test_tray_icon_is_96px_png_under_50kb():
    tray = encode_tray(compose.direct(photo(), SUBJECT).image)
    assert inspect_tray(tray) == ()


def test_trim_borders_and_text_safe_direct_crop():
    framed = Image.new("RGB", (700, 700), "white")
    framed.paste(photo(500, 500), (100, 100))
    assert trim_borders(framed).size == (500, 500)
    meme = photo(600, 600)
    ImageDraw.Draw(meme).text((300, 570), "BOTTOM TEXT", fill="white", anchor="mm")
    top_half = Crop(0.0, 0.0, 1.0, 0.5)  # a crop that would cut the bottom caption off
    with_text = compose.direct(meme, top_half, has_text=True).image.getchannel("A").getbbox()
    without_text = compose.direct(meme, top_half, has_text=False).image.getchannel("A").getbbox()
    assert with_text is not None and without_text is not None
    assert with_text[3] - with_text[1] > 1.8 * (without_text[3] - without_text[1])
