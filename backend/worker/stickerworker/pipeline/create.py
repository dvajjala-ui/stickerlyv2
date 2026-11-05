"""Stage 3: analysed sources -> validated WhatsApp stickers -> `stickers` rows; source -> used.

Every output passes the WhatsApp validator, a takedown fingerprint check and the
policy decision before it is uploaded to `stickers/<id>.webp`.
"""
from __future__ import annotations

import hashlib
import uuid
from collections import Counter
from dataclasses import dataclass
from datetime import timedelta
from typing import Any

from PIL import Image

from ..ai.base import TrendBrief
from ..analysis import Analysis, Caption, InvalidAnalysis, analysis_from_row
from ..imaging import clip, compose, cutout, motion
from ..imaging.decode import VIDEO_MIMES, DecodeError, decode_image, sniff_mime
from ..imaging.encode import EncodeError, encode_animated, encode_static
from ..imaging.validate import InvalidSticker, require_valid
from ..policy import decide, screen_source
from ..store.base import SOURCES_BUCKET, STICKERS_BUCKET, Row
from ..textutil import unique
from .captions import CaptionSet, get_captions
from .context import Context
from .recipes import Plan, plan_recipes

LEASE_SECONDS = 900
MAX_ATTEMPTS = 3
CLIP_FRAME_MS = 100
STICKER_CACHE_SECONDS = 31_536_000


@dataclass
class Draft:
    recipe: str
    kind: str
    data: bytes
    caption: Caption | None = None
    ai_generated: bool = True
    judged: bool = True
    text: str = ""


def is_animated_source(row: Row) -> bool:
    return row.get("mime") in VIDEO_MIMES or int(row.get("frames") or 1) > 1


def quality_of(analysis: Analysis, row: Row) -> int:
    value = 0.7 * analysis.sticker_score + 0.3 * float(row.get("prescreen_score") or 0.0)
    return max(0, min(10, round(value)))


def credit(row: Row) -> dict[str, str] | None:
    data = {"site": row.get("site"), "url": row.get("page_url") or row.get("source_url"), "author": row.get("author")}
    cleaned = {k: str(v) for k, v in data.items() if v}
    return cleaned or None


def trend_briefs(ctx: Context) -> list[TrendBrief]:
    rows = ctx.store.active_trends(ctx.now() - timedelta(hours=48))
    rows.sort(key=lambda r: (r.get("source") != "owner", -(r.get("score") or 0)))
    return [TrendBrief(r["id"], r["title"], tuple(r.get("keywords") or ())) for r in rows[:8]]


def _motion(base: Draft, composed: compose.Composed, seed: str) -> Draft:
    effect = motion.choose_effect(seed, composed.layout is not None and composed.plain is not None)
    if effect == "typewriter" and composed.layout and composed.plain and composed.caption_top is not None:
        frames, durations = motion.typewriter(composed.image, composed.plain, composed.layout, composed.caption_top)
    else:
        frames, durations = motion.SIMPLE_EFFECTS.get(effect, motion.pop)(composed.image)
    return Draft("motion", "animated", encode_animated(frames, durations), base.caption, base.ai_generated,
                 base.judged, base.text)


def _clip(ctx: Context, analysis: Analysis, data: bytes, mime: str, trends: list[TrendBrief]) -> list[Draft]:
    frames = clip.clip_frames(data, mime)
    found = CaptionSet((), True, "none") if analysis.has_text else get_captions(ctx, analysis, trends)
    caption = found.captions[0] if found.captions else None
    canvases = compose.clip_with_caption(frames, caption.text if caption else None)
    if canvases is None:  # caption could not fit: keep the clip without it
        caption = None
        canvases = compose.clip_with_caption(frames, None) or []
    if len(canvases) < 2:
        raise clip.ClipError("clip_too_short")
    animated = encode_animated(canvases, [CLIP_FRAME_MS] * len(canvases))
    return [Draft("caption" if caption else "direct", "animated", animated, caption, ai_generated=caption is not None,
                  judged=found.judged, text="" if caption else analysis.text)]


def _statics(ctx: Context, row: Row, analysis: Analysis, plan: Plan, image: Image.Image,
             trends: list[TrendBrief]) -> list[tuple[Draft, compose.Composed]]:
    base = plan.recipes[0]
    if base == "direct":
        composed = compose.direct(image, analysis.crop, analysis.has_text)
        return [(Draft("direct", "static", encode_static(composed.image), ai_generated=False, text=analysis.text),
                 composed)]
    found = get_captions(ctx, analysis, trends)
    subject = None
    if base == "cutout":
        try:
            subject = cutout.cut_subject(image, ctx.env.rembg_model)
        except cutout.CutoutUnavailable as error:
            ctx.log(f"create: cutout skipped for {row['id']} ({error})")
    out: list[tuple[Draft, compose.Composed]] = []
    for caption in found.captions:
        made = compose.cutout(subject, caption.text) if subject is not None else \
            compose.captioned(image, analysis.crop, caption.text)
        if made is not None:
            recipe = "cutout" if subject is not None else "caption"
            out.append((Draft(recipe, "static", encode_static(made.image), caption, True, found.judged), made))
    if not out:  # no usable caption: the reaction still works without text
        made = (compose.cutout(subject, None) if subject is not None else None) or \
            compose.direct(image, analysis.crop, analysis.has_text)
        recipe = "cutout" if subject is not None else "direct"
        out.append((Draft(recipe, "static", encode_static(made.image), ai_generated=subject is not None), made))
    return out


def render(ctx: Context, row: Row, analysis: Analysis, plan: Plan, data: bytes,
           trends: list[TrendBrief]) -> list[Draft]:
    mime = sniff_mime(data) or row.get("mime", "")
    if "clip" in plan.recipes:
        return _clip(ctx, analysis, data, mime, trends)
    statics = _statics(ctx, row, analysis, plan, decode_image(data).image, trends)
    drafts = [draft for draft, _ in statics]
    if "motion" in plan.recipes:
        drafts.append(_motion(*statics[0], seed=row["id"]))
    return drafts


def publish_draft(ctx: Context, row: Row, analysis: Analysis, draft: Draft, trends: list[TrendBrief]) -> str:
    """Validate, decide, upload and insert one sticker. Returns its status or 'rejected:<why>'."""
    try:
        require_valid(draft.data, draft.kind)
    except InvalidSticker as error:
        return f"rejected:invalid_output:{error}"
    sha = hashlib.sha256(draft.data).hexdigest()
    if ctx.takedowns.match(sha256=sha):
        return "rejected:takedown"
    quality = quality_of(analysis, row)
    decision = decide(analysis, draft.caption, protected=ctx.protected, policy=ctx.settings.publish,
                      quality=quality, site=str(row.get("site") or ""), judged=draft.judged)
    if decision.rejected:
        return "rejected:" + ",".join(decision.reasons)
    sticker_id = str(uuid.uuid4())
    path = f"{sticker_id}.webp"
    trend_id = next((t.id for t in trends if draft.caption and draft.caption.trend
                     and t.title.casefold() == draft.caption.trend.casefold()), None)
    caption_text = draft.caption.text if draft.caption else (draft.text[:200] or None)
    language = draft.caption.language if draft.caption else (analysis.language if analysis.has_text else None)
    ctx.store.upload(STICKERS_BUCKET, path, draft.data, "image/webp", cache_seconds=STICKER_CACHE_SECONDS)
    ctx.store.insert_sticker({
        "id": sticker_id, "source_id": row["id"], "recipe": draft.recipe, "kind": draft.kind,
        "rating": decision.rating, "category": analysis.category, "caption": caption_text, "language": language,
        "tags": list(unique([*analysis.tags, *analysis.mood], 20)),
        "emojis": list((draft.caption.emojis if draft.caption and draft.caption.emojis else analysis.emojis)[:3]),
        "description": analysis.description or None, "file_path": path, "sha256": sha, "phash": row.get("phash"),
        "bytes": len(draft.data), "ai_generated": draft.ai_generated, "credit": credit(row), "quality": quality,
        "trend_id": trend_id, "status": decision.status, "review_note": ",".join(decision.reasons) or None,
    })
    return decision.status


def process(ctx: Context, row: Row, trends: list[TrendBrief], room: int) -> tuple[dict[str, Any], Counter[str]]:
    """Returns the source update and per-sticker outcome counts."""
    done: dict[str, Any] = {"locked_until": None}
    try:
        analysis = analysis_from_row(row, ctx.settings.categories)
    except InvalidAnalysis as error:
        return {**done, "status": "failed", "last_error": f"analysis:{error}"}, Counter(failed=1)
    takedown = ctx.takedowns.match(sha256=row["sha256"], phash=row.get("phash"), url=row.get("source_url"))
    if takedown:
        return {**done, "status": "taken_down", "last_error": takedown}, Counter(taken_down=1)
    verdict = screen_source(analysis, ctx.protected, ctx.settings.publish)
    if not verdict.allowed:
        return {**done, "status": "rejected", "last_error": "policy:" + ",".join(verdict.reasons)}, \
            Counter(rejected=1)
    plan = plan_recipes(analysis, animated_source=is_animated_source(row), cutout_available=cutout.available())
    if not plan.recipes:
        return {**done, "status": "rejected", "last_error": plan.reason}, Counter(rejected=1)
    data = ctx.store.download(SOURCES_BUCKET, row["storage_path"])
    drafts = render(ctx, row, analysis, plan, data, trends)
    outcomes: Counter[str] = Counter()
    for draft in drafts[:room]:
        result = publish_draft(ctx, row, analysis, draft, trends)
        outcomes[result.split(":", 1)[0]] += 1
        if result.startswith("rejected:"):
            outcomes[f"why:{result.split(':', 2)[1]}"] += 1
    created = outcomes["published"] + outcomes["draft"]
    if created:
        return {**done, "status": "used", "last_error": None}, outcomes
    reasons = sorted(k.removeprefix("why:") for k in outcomes if k.startswith("why:"))
    return {**done, "status": "rejected", "last_error": "no_sticker:" + ",".join(reasons or ["none"])}, outcomes


def run(ctx: Context, limit: int) -> dict[str, Any]:
    room = ctx.settings.budgets.max_new_stickers_per_day - ctx.store.count_stickers_since(ctx.day_start())
    if room <= 0:
        return {"skipped": "daily_sticker_cap"}
    rows = ctx.store.claim_sources("analyzed", "analyzed", limit, LEASE_SECONDS)
    trends = trend_briefs(ctx) if rows else []
    totals: Counter[str] = Counter(claimed=len(rows))
    for row in rows:
        if room <= 0:
            ctx.store.update_source(row["id"], {"locked_until": None,
                                                "attempts": max(0, int(row.get("attempts", 1)) - 1)})
            totals["deferred"] += 1
            continue
        try:
            fields, outcomes = process(ctx, row, trends, room)
        except (DecodeError, clip.ClipError, EncodeError) as error:
            fields, outcomes = {"locked_until": None, "status": "failed", "last_error": f"render:{error}"}, Counter()
        except Exception as error:  # noqa: BLE001 - keep the queue moving; retry later
            retry = row.get("attempts", 0) < MAX_ATTEMPTS
            fields = {"locked_until": None, "status": "analyzed" if retry else "failed",
                      "last_error": f"{type(error).__name__}:{error}"[:300]}
            outcomes = Counter(errors=1)
        ctx.store.update_source(row["id"], fields)
        room -= outcomes["published"] + outcomes["draft"]
        totals.update({k: v for k, v in outcomes.items() if not k.startswith("why:")})
        totals[f"source_{fields['status']}"] += 1
    return dict(totals)
