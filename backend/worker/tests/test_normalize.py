"""normalize_analysis / normalize_batch / normalize_caption: forgiving parsing of flaky model JSON.

These protect the pipeline from the vision model's loose output (string booleans, odd casing, terse
lists, missing fields), while still rejecting unusable/unsafe-field responses."""
from __future__ import annotations

import pytest

from stickerworker.analysis import (
    Analysis, InvalidAnalysis, normalize_analysis, normalize_batch, normalize_caption,
)
from stickerworker.settings import DEFAULT_ROWS

CATS = tuple(DEFAULT_ROWS["categories"]["enabled"])


def base(**over):
    item = {"kind": "reaction", "has_text": False, "nsfw": "none", "minors_likely": False, "real_person": True,
            "hate_or_slur": False, "violence_or_gore": "none", "category": "reactions", "language": "none",
            "sticker_score": 7, "best_recipe": "caption", "mood": ["shocked"],
            "tags": ["wow", "omg", "shocked", "reaction", "face"], "description": "a surprised face",
            "caption_ideas": ["kya baat hai", "arre waah"]}
    item.update(over)
    return item


def test_valid_item_parses_to_analysis():
    a = normalize_analysis(base(), CATS)
    assert isinstance(a, Analysis) and a.kind == "reaction" and a.nsfw == "none" and a.sticker_score == 7


def test_string_booleans_are_accepted():
    a = normalize_analysis(base(minors_likely="false", real_person="TRUE", hate_or_slur="false", has_text="true"), CATS)
    assert a.minors_likely is False and a.real_person is True and a.has_text is True


@pytest.mark.parametrize("field", ["minors_likely", "real_person", "hate_or_slur", "has_text"])
def test_non_boolean_safety_fields_are_rejected(field):
    with pytest.raises(InvalidAnalysis):
        normalize_analysis(base(**{field: "maybe"}), CATS)


def test_bad_nsfw_is_rejected_but_odd_casing_is_normalised():
    with pytest.raises(InvalidAnalysis):
        normalize_analysis(base(nsfw="spicy"), CATS)
    assert normalize_analysis(base(nsfw="Suggestive"), CATS).nsfw == "suggestive"


def test_violence_tolerates_boolean_and_rejects_garbage():
    assert normalize_analysis(base(violence_or_gore=True), CATS).violence_or_gore == "graphic"
    assert normalize_analysis(base(violence_or_gore=False), CATS).violence_or_gore == "none"
    with pytest.raises(InvalidAnalysis):
        normalize_analysis(base(violence_or_gore="a lot"), CATS)


def test_unknown_kind_and_category_fall_back():
    a = normalize_analysis(base(kind="sticker", category="unknowncat"), CATS)
    assert a.kind == "other" and a.category == CATS[0]
    baby = normalize_analysis(base(kind="baby", category="nope"), CATS)
    assert baby.category == ("baby" if "baby" in CATS else CATS[0])


def test_sticker_score_clamps_and_tolerates_bad_types():
    assert normalize_analysis(base(sticker_score=99), CATS).sticker_score == 10
    assert normalize_analysis(base(sticker_score=-4), CATS).sticker_score == 0
    assert normalize_analysis(base(sticker_score="not a number"), CATS).sticker_score == 0
    assert normalize_analysis(base(sticker_score="8"), CATS).sticker_score == 8


def test_tags_accept_a_comma_string_and_get_topped_up_when_terse():
    a = normalize_analysis(base(tags="funny, office", mood=["tired"], description="monday at work"), CATS)
    assert len(a.tags) >= 5  # filled from mood/kind/category/description so search stays useful


def test_caption_ideas_empty_when_text_is_present():
    assert normalize_analysis(base(has_text=True, text="already a meme"), CATS).caption_ideas == ()
    assert len(normalize_analysis(base(has_text=False), CATS).caption_ideas) >= 1


def test_batch_maps_by_image_index_and_checks_count():
    items = [base(description="second", image=1), base(description="first", image=0)]
    out = normalize_batch({"images": items}, CATS, 2)
    assert [a.description for a in out] == ["first", "second"]  # reordered by index
    with pytest.raises(InvalidAnalysis):
        normalize_batch({"images": items}, CATS, 3)  # wrong count
    # without usable indices, input order is kept
    plain = normalize_batch([base(description="x"), base(description="y")], CATS, 2)
    assert [a.description for a in plain] == ["x", "y"]


def test_caption_normalisation_defaults_and_rejects_empty():
    assert normalize_caption("not a dict") is None
    assert normalize_caption({"text": "   "}) is None
    c = normalize_caption({"text": "bas kar bhai"})
    assert c.text == "bas kar bhai" and c.language == "hinglish" and c.style == "reaction" and c.rating == "general"
    adult = normalize_caption({"text": "savage line", "rating": "adult", "style": "savage", "language": "en"})
    assert adult.rating == "adult" and adult.language == "en"
