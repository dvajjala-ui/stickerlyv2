"""Content-safety policy matrix: minors, adult lane, explicit, protected people, hate, review gates."""
from __future__ import annotations

import pytest
from conftest import make_analysis, policy

from stickerworker.analysis import Caption
from stickerworker.lexicon import lexical_flags
from stickerworker.policy import decide, mentions_protected, name_matches, rate, screen_source
from stickerworker.settings import DEFAULT_PROTECTED_PEOPLE

PROTECTED = [name for name, _ in DEFAULT_PROTECTED_PEOPLE]
GENERAL = Caption("Kya scene hai bhai", "hinglish", "reaction", "general")
DOUBLE = Caption("Shaadi ke baad samjhoge", "hinglish", "double_meaning", "adult")
FLIRTY = Caption("Itna bhi mat dekho", "hinglish", "flirty", "adult")
SAVAGE = Caption("Aukaat mein reh", "hinglish", "savage", "adult")


def verdict(analysis=None, caption=None, *, quality=8, site="reddit", judged=True, adult_floor=False, **policy_overrides):
    return decide(analysis or make_analysis(), caption, protected=PROTECTED, policy=policy(**policy_overrides),
                  quality=quality, site=site, judged=judged, adult_floor=adult_floor)


# ------------------------------------------------------------------ hard rejects
@pytest.mark.parametrize("fields, reason", [
    ({"nsfw": "explicit"}, "explicit_image"),
    ({"nsfw": "explicit", "kind": "cartoon", "category": "adult"}, "explicit_image"),
    ({"hate_or_slur": True}, "hate_or_slur"),
    ({"violence_or_gore": "graphic"}, "graphic_violence"),
    ({"has_text": True, "text": "send nudes"}, "explicit_text"),
    ({"has_text": True, "text": "sab chamar hai"}, "slur_text"),
    ({"real_person": True, "nsfw": "suggestive", "category": "adult"}, "real_person_sexualised"),
    ({"real_person": True, "has_text": True, "text": "sexy bhabhi"}, "real_person_sexualised"),
])
def test_hard_rejects(fields, reason):
    result = verdict(make_analysis(**fields))
    assert result.status == "rejected"
    assert reason in result.reasons


def test_mild_violence_is_fine():
    assert verdict(make_analysis(violence_or_gore="mild")).status == "published"


# ------------------------------------------------------------------ minors
@pytest.mark.parametrize("fields", [
    {"minors_likely": True, "category": "adult"},
    {"minors_likely": True, "category": "dark"},
    {"minors_likely": True, "category": "relationship"},
    {"minors_likely": True, "nsfw": "suggestive"},
    {"kind": "baby", "category": "baby", "nsfw": "suggestive"},
    {"kind": "baby", "category": "relationship"},
    {"minors_likely": True, "has_text": True, "text": "bc kya scene hai"},
])
def test_minor_unsafe_sources_rejected(fields):
    result = verdict(make_analysis(**fields))
    assert result.status == "rejected"
    assert "minor_unsafe" in result.reasons


@pytest.mark.parametrize("caption", [DOUBLE, FLIRTY, SAVAGE, Caption("chutiya mat bano", rating="general")])
def test_minors_never_get_adult_text(caption):
    result = verdict(make_analysis(kind="baby", category="baby", minors_likely=True), caption)
    assert result.status == "rejected"
    assert "minor_adult_caption" in result.reasons


def test_baby_content_is_always_general():
    result = verdict(make_analysis(kind="baby", category="baby", minors_likely=True), GENERAL)
    assert (result.status, result.rating) == ("published", "general")
    assert rate(make_analysis(kind="baby", category="adult"), DOUBLE) == "general"


# ------------------------------------------------------------------ adult lane
def test_adult_category_is_adult_rated():
    assert verdict(make_analysis(category="adult", kind="cartoon"), GENERAL).rating == "adult"


def test_double_meaning_caption_on_cartoon_is_adult():
    result = verdict(make_analysis(kind="cartoon", category="funny"), DOUBLE)
    assert (result.status, result.rating) == ("published", "adult")


def test_suggestive_cartoon_is_adult_not_general():
    result = verdict(make_analysis(kind="cartoon", category="funny", nsfw="suggestive"))
    assert (result.status, result.rating) == ("published", "adult")


def test_real_person_flirty_caption_rejected_but_savage_allowed():
    person = make_analysis(real_person=True, category="relationship")
    assert "real_person_sexualised" in verdict(person, FLIRTY).reasons
    assert "real_person_sexualised" in verdict(person, DOUBLE).reasons
    savage = verdict(person, SAVAGE)
    assert (savage.status, savage.rating) == ("published", "adult")


def test_profanity_caption_forces_adult_rating():
    result = verdict(make_analysis(), Caption("bc kya bakwas hai", rating="general"))
    assert result.rating == "adult"


@pytest.mark.parametrize("analysis, caption", [
    (make_analysis(category="adult", kind="cartoon"), None),
    (make_analysis(kind="cartoon"), DOUBLE),
    (make_analysis(kind="cartoon", nsfw="suggestive"), None),
])
def test_adult_lane_off_rejects_adult_content(analysis, caption):
    result = verdict(analysis, caption, adult_lane=False)
    assert result.status == "rejected"
    assert "adult_lane_off" in result.reasons


def test_general_content_unaffected_by_adult_lane_off():
    assert verdict(make_analysis(), GENERAL, adult_lane=False).status == "published"


# ------------------------------------------------------------------ captions
@pytest.mark.parametrize("text, reason", [
    ("lund le lo", "explicit_caption"),
    ("ch0d diya", "explicit_caption"),
    ("ye sab katua hai", "slur_caption"),
    ("Amitabh Bachchan be like", "protected_person_caption"),
    ("Bilkul jhakaas!", "protected_person_caption"),
])
def test_bad_captions_rejected(text, reason):
    result = verdict(make_analysis(), Caption(text))
    assert result.status == "rejected"
    assert reason in result.reasons


# ------------------------------------------------------------------ protected people (court orders)
@pytest.mark.parametrize("figure", [
    "Amitabh Bachchan", "amitabh bachchan", "Shri Amitabh Bachchan ji", "Amitab Bachan", "Aishwarya Rai",
    "Hrithik", "Hritik Roshan", "ANIL KAPOOR", "Arijit Singh", "Karan Johar", "Kapoor",
])
def test_protected_public_figures_rejected(figure):
    result = verdict(make_analysis(real_person=True, public_figure=figure))
    assert result.status == "rejected"
    assert any(r.startswith("protected_person:") for r in result.reasons)


@pytest.mark.parametrize("figure", ["Ranbir Kapoor", "Jaya Bachchan", "Shah Rukh Khan", "Diljit Dosanjh"])
def test_unprotected_public_figures_published(figure):
    assert verdict(make_analysis(real_person=True, public_figure=figure)).status == "published"


def test_protected_person_found_in_text_and_tags():
    assert verdict(make_analysis(has_text=True, text="big b ka swag")).status == "rejected"
    assert verdict(make_analysis(tags=("amitabh", "bachchan", "angry", "old", "movie"))).status == "rejected"
    assert verdict(make_analysis(description="hrithik roshan dancing")).status == "rejected"


def test_name_matching_details():
    assert name_matches("Aishwarya Rai Bachchan", "Aishwarya Rai Bachchan")
    assert not name_matches("Jaya Bachchan", "Amitabh Bachchan")
    assert not name_matches("", "Amitabh Bachchan")
    assert mentions_protected("we love arijit singh songs", PROTECTED) == "Arijit Singh"
    assert mentions_protected("abhishek from accounts", PROTECTED) is None  # a lone common first name in text


# ------------------------------------------------------------------ celebrities, review gates
def test_celebrity_modes():
    star = make_analysis(real_person=True, public_figure="Shah Rukh Khan")
    assert verdict(star, celebrities="review").status == "draft"
    assert "celebrity_blocked" in verdict(star, celebrities="block").reasons
    assert verdict(star, celebrities="publish").status == "published"


@pytest.mark.parametrize("kwargs, reason", [
    ({"quality": 5}, "below_min_quality"),
    ({"site": "Pinterest", "require_review_sites": frozenset({"pinterest"})}, "site_requires_review"),
    ({"auto_publish": False}, "auto_publish_off"),
])
def test_review_gates_make_drafts(kwargs, reason):
    result = verdict(make_analysis(), GENERAL, **kwargs)
    assert result.status == "draft"
    assert reason in result.reasons


def test_unjudged_caption_is_draft_but_direct_is_not():
    assert verdict(make_analysis(), GENERAL, judged=False).status == "draft"
    assert verdict(make_analysis(), None, judged=False).status == "published"


def test_screen_source_is_pure_and_repeatable():
    analysis = make_analysis(nsfw="explicit", hate_or_slur=True)
    first = screen_source(analysis, PROTECTED, policy())
    assert first == screen_source(analysis, PROTECTED, policy())
    assert first.reasons == ("explicit_image", "hate_or_slur")


# ------------------------------------------------------------------ lexicon
@pytest.mark.parametrize("text, flags", [
    ("s3xy look", {"suggestive"}),
    ("sexyyyy", {"suggestive"}),
    ("chutiya hai kya", {"profanity"}),
    ("tum chutia hoti", {"profanity"}),  # the spelling that leaked into a general pack (7 Nov)
    ("madarchod scene", {"explicit"}),
    ("behenchod kya bakwas", {"explicit"}),
    ("मादरचोद", {"explicit"}),
    ("cockroach in the kitchen", set()),
    ("kya chhakka mara", set()),
    ("chura liya hai tumne", set()),
    ("18+ only joke", {"suggestive"}),
    ("neech jaat", {"slur"}),
])
def test_lexical_flags(text, flags):
    assert set(lexical_flags(text)) == flags


def test_adult_floor_keeps_18plus_topic_content_out_of_general():
    clean = make_analysis(category="funny", nsfw="none")  # model picked a general category
    assert verdict(clean).rating == "general"                      # no floor -> general
    assert verdict(clean, adult_floor=True).rating == "adult"      # 18+ topic -> adult lane
    # a minor is never floored to adult (stays general; other rules handle safety)
    minor = make_analysis(category="funny", nsfw="none", minors_likely=True)
    assert verdict(minor, adult_floor=True).rating == "general"
