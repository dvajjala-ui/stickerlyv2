"""Quality gate: local scorer on synthetic Pillow images, the gate's decisions, and the optional vision judge."""
from __future__ import annotations

import dataclasses
import io
import json
from typing import Any

import httpx
import numpy as np
import pytest
from PIL import Image, ImageDraw, ImageFilter, ImageFont
from samples import photo

from stickerworker.ai import Budget, FakeAI, QualityInput, QualityVerdict
from stickerworker.ai.base import AIInvalid, AIUnavailable
from stickerworker.ai.groq import GroqClient
from stickerworker.ai.groq_ai import GroqAI, parse_quality_verdict
from stickerworker.ai.schemas import QUALITY_ISSUES, quality_schema
from stickerworker.analysis import Crop
from stickerworker.config import Env
from stickerworker.imaging import compose, text
from stickerworker.imaging.encode import encode_animated, encode_static
from stickerworker.imaging.metrics import laplacian_variance
from stickerworker.imaging.quality import (
    BLANK_CAP,
    BLURRY,
    HARD_CAP,
    MIN_CAPTION_CONTRAST,
    score_image,
    score_sticker,
    with_judge,
)
from stickerworker.pipeline.gate import QualityGate
from stickerworker.settings import QualityGate as GateSettings
from stickerworker.settings import Recipes, parse_settings

SUBJECT = Crop(0.12, 0.1, 0.76, 0.8)
TRANSPARENT = (0, 0, 0, 0)


def captioned(image: Image.Image, caption: str = "Kya scene hai bhai") -> compose.Composed:
    made = compose.captioned(image, SUBJECT, caption)
    assert made is not None and made.caption_box is not None
    return made


def light_band_picture() -> Image.Image:
    """A sharp photo whose bottom band is flat and light (where a caption will sit)."""
    picture = photo(seed=9)
    ImageDraw.Draw(picture).rectangle((0, 360, 640, 480), fill=(236, 236, 232))
    return picture


# ------------------------------------------------------------------ local scorer
def test_good_sticker_scores_full_marks_static_and_animated():
    made = captioned(photo(seed=11))
    report = score_sticker(encode_static(made.image), caption_box=made.caption_box)
    assert report.score == 100 and report.reasons == () and not report.capped
    assert report.signals["caption_contrast"] >= MIN_CAPTION_CONTRAST and report.signals["sharpness"] > 100
    frames = [made.image, made.image.rotate(3), made.image.rotate(-3), made.image]
    animated = score_sticker(encode_animated(frames, [600, 80, 80, 900]), caption_box=made.caption_box)
    assert animated.score == 100 and animated.signals["frames"] == 4 and animated.signals["duration_ms"] == 1660
    assert json.loads(json.dumps(animated.signals)) == animated.signals  # stored as jsonb


def test_blank_stickers_are_capped_near_zero():
    empty = score_image(Image.new("RGBA", (512, 512), TRANSPARENT))
    card = Image.new("RGBA", (512, 512), TRANSPARENT)
    ImageDraw.Draw(card).rounded_rectangle((16, 76, 496, 436), radius=20, fill=(120, 140, 160, 255))
    flat = score_image(card)
    for report in (empty, flat):
        assert "blank" in report.reasons and report.score <= BLANK_CAP and report.capped


def test_flat_colour_cutout_shape_is_neither_blank_nor_blurry():
    heart = Image.new("RGBA", (520, 520), TRANSPARENT)
    draw = ImageDraw.Draw(heart)
    for box in ((60, 80, 270, 290), (250, 80, 460, 290)):
        draw.ellipse(box, fill=(220, 30, 60, 255))
    draw.polygon([(70, 220), (450, 220), (260, 470)], fill=(220, 30, 60, 255))
    made = compose.cutout(heart, "Pyaar hai")
    assert made is not None
    report = score_sticker(encode_static(made.image), caption_box=made.caption_box)
    assert report.signals["flat"] and report.signals["fill"] < 0.85
    assert report.score == 100 and report.reasons == ()


def test_tiny_subject_is_a_hard_problem_and_a_small_one_a_mild_penalty():
    tiny = Image.new("RGBA", (512, 512), TRANSPARENT)
    tiny.alpha_composite(photo(seed=5).resize((110, 80)).convert("RGBA"), (200, 210))
    report = score_image(tiny)
    assert {"tiny_subject", "mostly_transparent"} <= set(report.reasons)
    assert report.capped and report.score <= HARD_CAP
    small = Image.new("RGBA", (512, 512), TRANSPARENT)
    small.alpha_composite(photo(seed=5).resize((240, 180)).convert("RGBA"), (136, 166))
    mild = score_image(small)
    assert mild.reasons == ("small_subject",) and 60 <= mild.score < 100 and not mild.capped


def test_blurry_picture_is_caught_even_under_a_crisp_caption():
    made = captioned(photo(seed=13).filter(ImageFilter.GaussianBlur(6)))
    whole = laplacian_variance(np.asarray(made.image.convert("L"), dtype=np.float32))
    assert whole > 1000  # caption and card edges alone would look sharp
    report = score_sticker(encode_static(made.image), caption_box=made.caption_box)
    assert report.reasons == ("blurry",) and report.signals["sharpness"] < BLURRY
    assert report.score < 60 and not report.capped  # a vision judge may still overrule a blur verdict
    slight = captioned(photo(seed=13).filter(ImageFilter.GaussianBlur(2)))
    soft = score_sticker(encode_static(slight.image), caption_box=slight.caption_box)
    assert soft.reasons == ("soft",) and 60 <= soft.score < 100


def test_dim_but_sharp_photo_is_not_called_blurry():
    dim = Image.fromarray(np.clip(np.asarray(photo(seed=3), np.float32) * 0.25 + 20, 0, 255).astype(np.uint8))
    made = captioned(dim)
    report = score_sticker(encode_static(made.image), caption_box=made.caption_box)
    assert report.signals["sharpness"] < 60  # little absolute Laplacian energy...
    assert report.signals["sharpness_rel"] > 0.04 and report.reasons == ()  # ...but plenty for its contrast


def test_low_contrast_caption_is_held_and_our_outlined_caption_passes():
    font = ImageFont.truetype(str(text.LATIN_FONT), 56)
    faint = compose.direct(light_band_picture(), Crop()).image
    draw = ImageDraw.Draw(faint)
    draw.text((256, 372), "KYA SCENE HAI", font=font, fill=(206, 206, 206, 255), anchor="ma")
    box = draw.textbbox((256, 372), "KYA SCENE HAI", font=font, anchor="ma")
    low = score_image(faint, caption_box=box)
    assert "caption_low_contrast" in low.reasons and low.signals["caption_contrast"] < MIN_CAPTION_CONTRAST
    assert low.capped and low.score <= HARD_CAP
    outlined = compose.direct(light_band_picture(), Crop()).image
    layout = text.layout_caption("KYA SCENE HAI", 400, 120)
    assert layout is not None
    text.draw_caption(outlined, layout, 372)
    good = score_image(outlined, caption_box=text.ink_box(layout, 372))
    assert good.score == 100 and good.signals["caption_contrast"] > 8


def test_caption_box_outside_the_canvas_counts_as_clipped():
    made = captioned(photo(seed=14))
    x0, y0, x1, y1 = made.caption_box
    report = score_image(made.image, caption_box=(x0, y0 + 60, x1, y1 + 60))
    assert "caption_clipped" in report.reasons and report.score <= HARD_CAP


def test_ink_box_covers_exactly_what_draw_caption_inks():
    layout = text.layout_caption("Jab boss bole weekend pe bhi aana hai", 480, 168)
    assert layout is not None
    canvas = Image.new("RGBA", (512, 512), TRANSPARENT)
    text.draw_caption(canvas, layout, 40)
    inked = canvas.getchannel("A").getbbox()
    box = text.ink_box(layout, 40)
    assert inked is not None
    assert box[0] <= inked[0] and box[1] <= inked[1] and box[2] >= inked[2] and box[3] >= inked[3]
    assert all(abs(a - b) <= 3 for a, b in zip(box, inked, strict=True))


@pytest.mark.parametrize("durations, reason", [([60, 60, 60], "animation_too_short"),
                                                ([400, 400, 400, 400], "animation_choppy"),
                                                ([500, 500], "animation_few_frames")])
def test_animation_sanity(durations, reason):
    base = captioned(photo(seed=15)).image
    frames = [base.rotate(2 * i) for i in range(len(durations))]
    report = score_sticker(encode_animated(frames, durations))
    assert reason in report.reasons and report.signals["frames"] == len(durations)
    assert (report.score <= HARD_CAP) == (reason == "animation_too_short")


def test_duplicate_and_judge_blending():
    made = captioned(photo(seed=16))
    duplicate = score_image(made.image, caption_box=made.caption_box, duplicate=("sticker-1", 2))
    assert duplicate.reasons == ("near_duplicate",) and duplicate.score == HARD_CAP
    assert duplicate.signals["duplicate_of"] == "sticker-1" and duplicate.signals["duplicate_distance"] == 2
    assert with_judge(duplicate, 10).score == HARD_CAP  # hard problems stay capped
    blurry = score_image(BLURRED.image, caption_box=BLURRED.caption_box)
    assert blurry.reasons == ("blurry",)
    rescued = with_judge(blurry, 9)
    assert rescued.score == round(0.5 * blurry.heuristic + 45) and rescued.signals["judge"]["score"] == 9
    sunk = with_judge(score_image(made.image, caption_box=made.caption_box), 1, ["subject_cut_off"])
    assert sunk.score == 55 and sunk.reasons == ("judge:subject_cut_off",)


# ------------------------------------------------------------------ settings + gate decisions
def test_quality_gate_settings_defaults_and_bounds():
    # Owner 2026-11-11: gate off by default (every safe sticker publishes); animated recipes paused.
    assert parse_settings({}).quality == GateSettings(enabled=False, min_score=60, vision_judge=False, judge_band=20)
    assert parse_settings({}).recipes == Recipes(motion=False, clip=False)
    assert parse_settings({"recipes": {"motion": True, "clip": "yes"}}).recipes == Recipes(motion=True, clip=False)
    parsed = parse_settings({"quality_gate": {"enabled": True, "min_score": 250, "vision_judge": "yes",
                                              "judge_band": -5}}).quality
    assert parsed == GateSettings(enabled=True, min_score=100, vision_judge=False, judge_band=0)


def with_gate(ctx, **overrides: Any) -> None:
    ctx.settings = dataclasses.replace(ctx.settings, quality=dataclasses.replace(ctx.settings.quality, **overrides))


BLURRED = captioned(photo(seed=13).filter(ImageFilter.GaussianBlur(6)))
BLURRED_WEBP = encode_static(BLURRED.image)
GOOD = captioned(photo(seed=18))
GOOD_WEBP = encode_static(GOOD.image)


def check(gate: QualityGate, made: compose.Composed, data: bytes, source_id: str, **kwargs: Any):
    return gate.check(data, kind="static", source_id=source_id, caption_box=made.caption_box, **kwargs)


def test_gate_holds_only_stickers_that_would_publish(ctx):
    with_gate(ctx, enabled=True)
    gate = QualityGate(ctx, known=[])
    weak = check(gate, BLURRED, BLURRED_WEBP, "s1")
    good = check(gate, GOOD, GOOD_WEBP, "s2")
    assert gate.status("published", weak) == "held" and gate.status("published", good) == "published"
    assert gate.status("draft", weak) == "draft"
    assert gate.note(weak) == f"quality_gate:{weak.score}<60:blurry"
    with_gate(ctx, enabled=False)
    assert QualityGate(ctx, known=[]).status("published", weak) == "published"


def test_gate_flags_near_duplicates_from_other_sources_only(ctx, store):
    with_gate(ctx, enabled=True)
    picture = photo(seed=61)
    framed = Image.new("RGB", (picture.width + 160, picture.height + 160), "white")
    framed.paste(picture, (80, 80))  # a different file (prescreen pHash is 26 bits away) of the same picture
    first, second = encode_static(compose.direct(picture, Crop()).image), \
        encode_static(compose.direct(framed, Crop()).image)
    gate = QualityGate(ctx, known=[])
    report = gate.check(first, kind="static", source_id="src-a")
    assert report.reasons == ()
    gate.remember("sticker-a", report, "src-a", "published")
    again = gate.check(second, kind="static", source_id="src-b")
    assert again.reasons == ("near_duplicate",) and again.signals["duplicate_of"] == "sticker-a"
    assert gate.status("published", again) == "held"
    assert gate.check(second, kind="static", source_id="src-a").reasons == ()  # same source: a variant
    gate.remember("sticker-held", again, "src-b", "held")
    assert [k[0] for k in gate.known] == ["sticker-a"]  # held stickers never block a better copy
    # stored stickers count too: the store returns published/draft render fingerprints
    store.upload("stickers", "a.webp", first, "image/webp")
    store.insert_sticker({"id": "sticker-stored", "source_id": None, "recipe": "direct", "kind": "static",
                          "file_path": "a.webp", "sha256": "a" * 64, "bytes": len(first), "status": "published",
                          "render_phash": report.render_phash, "quality_score": 100})
    assert QualityGate(ctx).check(second, kind="static", source_id="src-c").signals["duplicate_of"] == \
        "sticker-stored"


# ------------------------------------------------------------------ optional vision judge
class CountingJudge:
    """Stub AI: counts judge calls; `fail` raises instead of answering."""
    provider = "stub"

    def __init__(self, remaining: int = 100, score: int = 9, fail: Exception | None = None) -> None:
        self._remaining = remaining
        self.score = score
        self.fail = fail
        self.items: list[QualityInput] = []

    def remaining(self, kind: str) -> int:
        return self._remaining

    def judge_quality(self, item: QualityInput) -> QualityVerdict:
        self.items.append(item)
        if self.fail is not None:
            raise self.fail
        return QualityVerdict(self.score, ())


def test_vision_judge_is_off_by_default(ctx):
    ctx.ai = CountingJudge()
    report = check(QualityGate(ctx, known=[]), BLURRED, BLURRED_WEBP, "s")
    assert ctx.ai.items == [] and "judge" not in report.signals and report.reasons == ("blurry",)


def test_vision_judge_only_sees_borderline_stickers_that_would_publish(ctx):
    with_gate(ctx, enabled=True, vision_judge=True)
    ctx.ai = CountingJudge(score=9)
    gate = QualityGate(ctx, known=[])
    assert "judge" not in check(gate, GOOD, GOOD_WEBP, "a").signals  # 100: clearly fine
    tiny = Image.new("RGBA", (512, 512), TRANSPARENT)
    tiny.alpha_composite(photo(seed=5).resize((110, 80)).convert("RGBA"), (200, 210))
    assert "judge" not in gate.check(encode_static(tiny), kind="static", source_id="b").signals  # hard: capped
    assert "judge" not in check(gate, BLURRED, BLURRED_WEBP, "c", wants_publish=False).signals  # a draft anyway
    rescued = check(gate, BLURRED, BLURRED_WEBP, "d", caption="Kya scene hai bhai")
    assert len(ctx.ai.items) == 1 and rescued.signals["judge"] == {"score": 9, "issues": []}
    assert gate.status("published", rescued) == "published" and gate.summary() == {"quality_judged": 1}
    item = ctx.ai.items[0]
    assert item.caption == "Kya scene hai bhai" and item.jpeg[:3] == b"\xff\xd8\xff"
    assert Image.open(io.BytesIO(item.jpeg)).size == (512, 512)


def test_vision_judge_skipped_when_the_vision_budget_is_spent(ctx):
    with_gate(ctx, enabled=True, vision_judge=True)
    ctx.ai = CountingJudge(remaining=0)
    gate = QualityGate(ctx, known=[])
    report = check(gate, BLURRED, BLURRED_WEBP, "s")
    assert ctx.ai.items == [] and report.signals["judge"] == {"skipped": "vision_budget_exhausted"}
    assert report.score == report.heuristic and gate.status("published", report) == "held"
    assert gate.summary() == {"quality_judge_skipped": 1, "quality_judge_stopped": "vision_budget_exhausted"}


def test_vision_judge_failure_stops_it_for_the_run(ctx):
    with_gate(ctx, enabled=True, vision_judge=True)
    ctx.ai = CountingJudge(fail=AIUnavailable("rate limited"))
    gate = QualityGate(ctx, known=[])
    for source in ("a", "b"):
        assert check(gate, BLURRED, BLURRED_WEBP, source).signals["judge"] == {"skipped": "ai_unavailable"}
    assert len(ctx.ai.items) == 1  # not asked again after the first failure


def ok_body(content: dict[str, Any]) -> dict[str, Any]:
    return {"choices": [{"index": 0, "finish_reason": "stop", "message": {"content": json.dumps(content)}}],
            "usage": {"prompt_tokens": 2300, "completion_tokens": 12}}


def test_groq_vision_judge_spends_one_vision_image_per_sticker(ctx):
    requests: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        requests.append(request)
        return httpx.Response(200, json=ok_body({"score": 9, "issues": ["none", "blurry"]}))

    ledger: list[dict[str, Any]] = []
    client = GroqClient(["gsk_test_key"], budget=Budget({"vision": 1, "text": 0}), ledger=ledger.append,
                        transport=httpx.MockTransport(handler), sleep=lambda s: None)
    with_gate(ctx, enabled=True, vision_judge=True)
    ctx.ai = GroqAI(client, Env())
    gate = QualityGate(ctx, known=[])
    first = check(gate, BLURRED, BLURRED_WEBP, "a")
    second = check(gate, BLURRED, BLURRED_WEBP, "b")
    assert first.signals["judge"] == {"score": 9, "issues": ["blurry"]} and "judge:blurry" in first.reasons
    assert second.signals["judge"] == {"skipped": "vision_budget_exhausted"}  # budget of one image spent
    assert len(requests) == 1 and client.budget.used["vision"] == 1
    assert [row["purpose"] for row in ledger] == ["quality_judge"]
    body = json.loads(requests[0].content)
    assert body["model"] == Env().vision_model and body["temperature"] == 0.0
    assert body["response_format"]["json_schema"]["name"] == "sticker_quality"
    assert body["messages"][1]["content"][1]["image_url"]["url"].startswith("data:image/jpeg;base64,")


def test_quality_schema_is_strict_and_answers_are_validated():
    schema = quality_schema()
    assert schema["additionalProperties"] is False and schema["required"] == ["score", "issues"]
    assert schema["properties"]["issues"]["items"]["enum"] == list(QUALITY_ISSUES)
    assert parse_quality_verdict({"score": 14, "issues": ["blurry", "made_up", "blurry"]}) == \
        QualityVerdict(10, ("blurry",))
    for bad in ({"issues": []}, {"score": "8", "issues": []}, {"score": True, "issues": []}, []):
        with pytest.raises(AIInvalid):
            parse_quality_verdict(bad)


def test_fake_judge_is_deterministic_and_logged(store):
    ai = FakeAI(store.log_ai_call)
    assert ai.judge_quality(QualityInput("x", b"jpeg", hints=("office", "reaction"))) == QualityVerdict(8, ())
    assert ai.judge_quality(QualityInput("y", b"jpeg", hints=("ugly", "selfie"))) == QualityVerdict(1, ("other",))
    assert {c["purpose"] for c in store.rows("ai_calls")} == {"quality_judge"}


def test_judge_calls_count_against_the_daily_vision_budget(store):
    from stickerworker.timeutil import ist_day_start, utcnow

    for ok in (True, False):  # an unusable answer was still billed
        store.log_ai_call({"provider": "groq", "model": "m", "purpose": "quality_judge", "ok": ok, "status": 200})
    store.log_ai_call({"provider": "groq", "model": "m", "purpose": "quality_judge", "ok": False, "status": 429})
    assert store.ai_usage_since(ist_day_start(utcnow()))["vision"] == 2
