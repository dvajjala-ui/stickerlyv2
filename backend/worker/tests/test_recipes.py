"""Recipe planning and caption sourcing."""
from __future__ import annotations

import pytest
from conftest import make_analysis

from stickerworker.ai.base import AIUnavailable, CaptionRequest
from stickerworker.analysis import Caption
from stickerworker.pipeline.captions import choose, get_captions
from stickerworker.pipeline.recipes import plan_recipes


@pytest.mark.parametrize("best", ["caption", "cutout", "direct"])
def test_pictures_with_text_always_go_direct(best):
    plan = plan_recipes(make_analysis(has_text=True, text="WHEN THE WIFI DROPS", best_recipe=best),
                        animated_source=False, cutout_available=True)
    assert plan.recipes[0] == "direct"
    assert "caption" not in plan.recipes and "cutout" not in plan.recipes


def test_reaction_without_text_gets_caption_and_motion():
    assert plan_recipes(make_analysis(), animated_source=False, cutout_available=False).recipes == \
        ("caption", "motion")


def test_motion_only_for_strong_stickers():
    assert plan_recipes(make_analysis(sticker_score=5), animated_source=False, cutout_available=False).recipes == \
        ("caption",)


def test_cutout_only_when_installed():
    wants_cutout = make_analysis(best_recipe="cutout")
    assert plan_recipes(wants_cutout, animated_source=False, cutout_available=True).recipes[0] == "cutout"
    assert plan_recipes(wants_cutout, animated_source=False, cutout_available=False).recipes[0] == "caption"


def test_animated_sources_become_clips():
    assert plan_recipes(make_analysis(has_text=True), animated_source=True, cutout_available=True).recipes == \
        ("clip",)


@pytest.mark.parametrize("fields, reason", [({"best_recipe": "skip"}, "model_skip"),
                                            ({"sticker_score": 2}, "low_sticker_score")])
def test_unusable_pictures_get_no_recipe(fields, reason):
    plan = plan_recipes(make_analysis(**fields), animated_source=False, cutout_available=True)
    assert plan.recipes == () and plan.reason == reason


def test_choose_prefers_romanised_general_then_one_adult():
    captions = [Caption("हद है यार", "hi"), Caption("Bas kar pagle"), Caption("Raat ko baat", rating="adult"),
                Caption("Second adult", rating="adult")]
    assert [c.text for c in choose(captions)] == ["Bas kar pagle", "Raat ko baat"]
    assert [c.text for c in choose(captions[:2])] == ["Bas kar pagle", "हद है यार"]


class SpyAI:
    """Records caption requests; returns a caption set including an unsafe line."""
    provider = "spy"

    def __init__(self, fail_judge: bool = False) -> None:
        self.requests: list[CaptionRequest] = []
        self.fail_judge = fail_judge

    def remaining(self, kind):
        return 10

    def write_captions(self, request):
        self.requests.append(request)
        return [Caption("Kya scene hai"), Caption("lund le lo"), Caption("Raat ko baat", style="flirty",
                                                                        rating="adult")]

    def judge_captions(self, analysis, captions):
        if self.fail_judge:
            raise AIUnavailable("budget")
        return [True] * len(captions)


def test_minor_pictures_never_request_or_keep_adult_captions(ctx):
    spy = SpyAI()
    ctx.ai = spy
    found = get_captions(ctx, make_analysis(kind="baby", category="baby", minors_likely=True), [])
    assert spy.requests[0].adult == 0 and spy.requests[0].adult_styles == ()
    assert [c.text for c in found.captions] == ["Kya scene hai"]  # explicit and adult lines filtered


def test_real_people_only_get_non_sexual_adult_styles(ctx):
    spy = SpyAI()
    ctx.ai = spy
    found = get_captions(ctx, make_analysis(real_person=True, category="relationship"), [])
    assert spy.requests[0].adult == 1
    assert set(spy.requests[0].adult_styles) == {"savage", "dark"}
    assert all(c.style != "flirty" for c in found.captions)


def test_judge_failure_marks_captions_unjudged(ctx):
    ctx.ai = SpyAI(fail_judge=True)
    found = get_captions(ctx, make_analysis(), [])
    assert found.captions and found.judged is False


def test_without_ai_falls_back_to_caption_ideas(ctx):
    ctx.ai = None
    found = get_captions(ctx, make_analysis(caption_ideas=("Sach mein?", "Arre waah")), [])
    assert found.origin == "ideas" and found.captions[0].text == "Sach mein?"
    assert found.judged is False  # nobody judged them: they become drafts
