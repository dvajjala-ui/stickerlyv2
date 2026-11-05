"""Outlined meme captions: white fill, thick black stroke, auto-wrap and auto-size.

Latin captions use Anton (Impact-style, OFL); any caption containing Devanagari is
set entirely in Noto Sans Devanagari ExtraBold (which also covers Latin), so mixed
Hinglish lines keep one consistent look. Correct Devanagari conjunct shaping needs
Pillow's raqm layout (libraqm + fribidi); see complex_shaping_available().
"""
from __future__ import annotations

import contextlib
import itertools
import math
import unicodedata
from dataclasses import dataclass
from functools import lru_cache
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont, features

from ..textutil import has_devanagari, strip_emoji

FONT_DIR = Path(__file__).resolve().parent.parent / "fonts"
LATIN_FONT = FONT_DIR / "Anton-Regular.ttf"
DEVANAGARI_FONT = FONT_DIR / "NotoSansDevanagari-Variable.ttf"
DEVANAGARI_WEIGHT = "ExtraBold"
FILL = (255, 255, 255, 255)
STROKE_FILL = (0, 0, 0, 255)
MIN_STROKE = 6
_VIRAMA = "\u094d"
_JOINERS = "\u200c\u200d"  # ZWNJ, ZWJ


def complex_shaping_available() -> bool:
    return bool(features.check_feature("raqm"))


@lru_cache(maxsize=128)
def load_font(path: str, size: int) -> ImageFont.FreeTypeFont:
    font = ImageFont.truetype(path, size)
    if path.endswith("Variable.ttf"):
        # FreeType without variation support keeps the regular weight, which still renders.
        with contextlib.suppress(OSError, ValueError):
            font.set_variation_by_name(DEVANAGARI_WEIGHT)
    return font


def font_for(text: str) -> Path:
    return DEVANAGARI_FONT if has_devanagari(text) else LATIN_FONT


def _bitmap(path: str, char: str) -> bytes:
    image = Image.new("L", (72, 72))
    ImageDraw.Draw(image).text((8, 4), char, font=load_font(path, 36), fill=255)
    return image.tobytes()


@lru_cache(maxsize=8)
def _notdef(path: str) -> bytes:
    return _bitmap(path, "\ue000")  # private-use code point: renders the .notdef box


@lru_cache(maxsize=4096)
def has_glyph(path: str, char: str) -> bool:
    return _bitmap(path, char) != _notdef(path)


def renderable(text: str, font_path: Path) -> str:
    """Drop emoji and characters the font cannot draw (no tofu boxes in stickers)."""
    kept: list[str] = []
    for char in strip_emoji(text):
        if char.isspace():
            kept.append(" ")
        elif (unicodedata.category(char).startswith("M") or char in _JOINERS or ord(char) < 128
              or has_glyph(str(font_path), char)):
            kept.append(char)
    return " ".join("".join(kept).split())


@dataclass(frozen=True)
class CaptionLayout:
    text: str
    lines: tuple[str, ...]
    font_path: str
    size: int
    stroke: int
    line_height: int
    offsets: tuple[int, ...]  # left x of each line relative to the block centre
    ink_top: int  # ink extent relative to the first line's ascender anchor
    width: int
    height: int


def stroke_for(size: int) -> int:
    return max(MIN_STROKE, round(size * 0.12))


def _width(font: ImageFont.FreeTypeFont, text: str, stroke: int) -> int:
    return math.ceil(font.getlength(text)) + 2 * stroke


def _greedy(words: list[str], font: ImageFont.FreeTypeFont, stroke: int, max_width: int) -> list[str] | None:
    lines: list[str] = []
    current = ""
    for word in words:
        candidate = f"{current} {word}".strip()
        if _width(font, candidate, stroke) <= max_width:
            current = candidate
            continue
        if not current or _width(font, word, stroke) > max_width:
            return None
        lines.append(current)
        current = word
    if current:
        lines.append(current)
    return lines


def _balanced(words: list[str], count: int, font: ImageFont.FreeTypeFont, stroke: int,
              max_width: int) -> list[str] | None:
    """Same line count as greedy, but with the widest line as narrow as possible."""
    if count <= 1 or len(words) > 16:
        return None
    best: tuple[int, list[str]] | None = None
    for cuts in itertools.combinations(range(1, len(words)), count - 1):
        bounds = (0, *cuts, len(words))
        lines = [" ".join(words[a:b]) for a, b in itertools.pairwise(bounds)]
        widest = max(_width(font, line, stroke) for line in lines)
        if widest <= max_width and (best is None or widest < best[0]):
            best = (widest, lines)
    return best[1] if best else None


def layout_caption(text: str, max_width: int, max_height: int, *, max_lines: int = 3, max_size: int = 80,
                   min_size: int = 28) -> CaptionLayout | None:
    """Largest font size whose wrapped caption fits the box; None if it cannot fit."""
    path = font_for(text)
    clean = renderable(text, path)
    if not clean:
        return None
    words = clean.split()
    latin = path == LATIN_FONT
    for size in range(max_size, min_size - 1, -2):
        font = load_font(str(path), size)
        stroke = stroke_for(size)
        lines = _greedy(words, font, stroke, max_width)
        if not lines or len(lines) > max_lines:
            continue
        lines = _balanced(words, len(lines), font, stroke, max_width) or lines
        ascent, descent = font.getmetrics()
        line_height = round((ascent + descent) * (0.86 if latin else 1.0)) + stroke // 2
        tops, bottoms, offsets = [], [], []
        for index, line in enumerate(lines):
            left, top, right, bottom = font.getbbox(line, stroke_width=stroke, anchor="la")
            tops.append(index * line_height + top)
            bottoms.append(index * line_height + bottom)
            offsets.append(-round(font.getlength(line) / 2))
        height = max(bottoms) - min(tops)
        width = max(_width(font, line, stroke) for line in lines)
        if height <= max_height:
            return CaptionLayout(clean, tuple(lines), str(path), size, stroke, line_height, tuple(offsets),
                                 min(tops), width, height)
    return None


def _units(line: str) -> list[str]:
    """Approximate grapheme clusters so a typewriter never splits a Devanagari conjunct."""
    units: list[str] = []
    for char in line:
        joins = units and (unicodedata.category(char).startswith("M") or char in _JOINERS
                           or units[-1].endswith(_VIRAMA))
        if joins:
            units[-1] += char
        else:
            units.append(char)
    return units


def visible_units(layout: CaptionLayout) -> int:
    return sum(len(_units(line)) for line in layout.lines)


def draw_caption(canvas: Image.Image, layout: CaptionLayout, top: int, center_x: int = 256,
                 upto: int | None = None) -> None:
    """Draw the caption with its ink box starting at `top`. `upto` limits visible units (typewriter)."""
    draw = ImageDraw.Draw(canvas)
    font = load_font(layout.font_path, layout.size)
    budget = upto if upto is not None else 10**9
    y = top - layout.ink_top
    for line, offset in zip(layout.lines, layout.offsets, strict=True):
        if budget <= 0:
            break
        units = _units(line)
        shown = "".join(units[:budget])
        budget -= len(units)
        if shown.strip():
            draw.text((center_x + offset, y), shown, font=font, fill=FILL, stroke_width=layout.stroke,
                      stroke_fill=STROKE_FILL, anchor="la")
        y += layout.line_height
