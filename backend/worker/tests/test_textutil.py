"""Caption tidy: strip baked-in handles/URLs and tame stretched vowels without breaking real words."""
from __future__ import annotations

from stickerworker.analysis import normalize_caption
from stickerworker.textutil import tidy_caption


def test_strips_handles_and_urls():
    assert tidy_caption("@theangryop tum bol") == "tum bol"
    assert tidy_caption("lol @meme_king") == "lol"
    assert tidy_caption("visit example.com now") == "visit now"
    assert tidy_caption("see www.site.in/abc haha") == "see haha"


def test_collapses_only_extreme_runs():
    assert tidy_caption("diyaaa") == "diyaa"          # 3+ -> 2
    assert tidy_caption("tereee kya") == "teree kya"  # 3 e -> 2
    assert tidy_caption("cooool") == "cool"           # 4 -> 2, real word survives
    assert tidy_caption("cool") == "cool"             # legit double untouched
    assert tidy_caption("nahi yaar") == "nahi yaar"   # no change


def test_blank_and_devanagari():
    assert tidy_caption("") == ""
    assert tidy_caption("  ") == ""
    assert tidy_caption("नमस्ते yaar") == "नमस्ते yaar"  # Devanagari untouched


def test_normalize_caption_applies_tidy():
    c = normalize_caption({"text": "@theangryop arre waaah"})
    assert c is not None and c.text == "arre waah"  # handle gone, waaah -> waah
    # a caption that becomes empty after stripping a lone handle is dropped
    assert normalize_caption({"text": "@someone"}) is None
