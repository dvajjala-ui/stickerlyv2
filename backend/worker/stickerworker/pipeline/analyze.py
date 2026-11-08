"""Stage 2 (budgeted vision): screened -> analyzing -> analyzed, highest prescreen_score first.

Up to 3 images per request (fewer when the TPM limit cannot fit them). When the
budget or rate limits stop the run, unprocessed sources go back to `screened`
with their attempt counter restored.
"""
from __future__ import annotations

from collections import Counter
from typing import Any

from ..ai.base import AIError, AIUnavailable, VisionInput
from ..ai.groq import RequestTooLarge
from ..analysis import Analysis
from ..imaging import clip
from ..imaging.decode import VIDEO_MIMES, DecodeError, decode_image, sniff_mime, vision_jpeg
from ..store.base import SOURCES_BUCKET, Row
from ..textutil import tokens
from ..timeutil import iso
from .context import Context

LEASE_SECONDS = 900
MAX_ATTEMPTS = 3


def hints(row: Row) -> tuple[str, ...]:
    words = tokens(" ".join(str(row.get(k) or "") for k in ("topic_hint", "site")))
    return tuple(dict.fromkeys(words))[:12]


def prepare(ctx: Context, row: Row) -> VisionInput:
    data = ctx.store.download(SOURCES_BUCKET, row["storage_path"])
    mime = sniff_mime(data) or row.get("mime", "")
    image = clip.poster(data, mime) if mime in VIDEO_MIMES else decode_image(data).image
    return VisionInput(row["id"], vision_jpeg(image), hints(row), float(row.get("prescreen_score") or 0.0),
                       str(row.get("ocr_text") or ""))


def analysis_fields(ctx: Context, analysis: Analysis) -> dict[str, Any]:
    stored = {**analysis.to_json(), "provider": getattr(ctx.ai, "provider", "unknown"),
              "model": ctx.env.vision_model if getattr(ctx.ai, "provider", "") == "groq" else "fake"}
    return {"analysis": stored, "kind": analysis.kind, "has_text": analysis.has_text, "language": analysis.language,
            "category": analysis.category, "mood": list(analysis.mood), "tags": list(analysis.tags),
            "emojis": list(analysis.emojis), "description": analysis.description, "nsfw": analysis.nsfw,
            "minors": analysis.minors_likely, "real_person": analysis.real_person,
            "public_figure": analysis.public_figure or None, "sticker_score": analysis.sticker_score,
            "analyzed_at": iso(ctx.now()), "status": "analyzed", "locked_until": None, "last_error": None,
            "attempts": 0}  # success: create gets a fresh attempt budget (P2)


def _release(ctx: Context, row: Row) -> None:
    ctx.store.update_source(row["id"], {"status": "screened", "locked_until": None,
                                        "attempts": max(0, int(row.get("attempts", 1)) - 1)})


def _retry_or_fail(ctx: Context, row: Row, error: str) -> str:
    status = "screened" if row.get("attempts", 0) < MAX_ATTEMPTS else "failed"
    ctx.store.update_source(row["id"], {"status": status, "locked_until": None, "last_error": error[:300]})
    return status


def run(ctx: Context, limit: int) -> dict[str, Any]:
    if ctx.ai is None:
        return {"skipped": "no_ai_provider"}
    remaining = ctx.ai.remaining("vision")
    if remaining <= 0:
        return {"skipped": "vision_budget_exhausted"}
    # Recover any rows a previous run left in 'analyzing' past their lease (e.g. the process was killed mid-run).
    reclaimed = ctx.store.reclaim_stale("analyzing", "screened") or 0
    queue = ctx.store.claim_sources("screened", "analyzing", min(limit, remaining), LEASE_SECONDS, by_score=True)
    counts: Counter[str] = Counter(claimed=len(queue))
    batch_size = max(1, ctx.ai.images_per_request())
    prepared: dict[str, VisionInput] = {}
    broken: set[str] = set()
    stopped = ""
    while queue:
        batch = queue[:batch_size]
        ready: list[Row] = []
        for row in batch:
            if row["id"] in broken:
                continue
            if row["id"] not in prepared:
                try:
                    prepared[row["id"]] = prepare(ctx, row)
                except (DecodeError, clip.ClipError) as error:
                    broken.add(row["id"])
                    ctx.store.update_source(row["id"], {"status": "failed", "locked_until": None,
                                                        "last_error": f"prepare:{error}"})
                    counts["failed"] += 1
                    continue
                except Exception as error:  # noqa: BLE001 - a download/store blip retries this row, never aborts the stage (P1)
                    broken.add(row["id"])
                    counts[_retry_or_fail(ctx, row, f"prepare:{type(error).__name__}:{error}")] += 1
                    continue
            ready.append(row)
        if not ready:
            queue = queue[len(batch):]
            continue
        try:
            analyses = ctx.ai.analyze([prepared[r["id"]] for r in ready], ctx.settings.categories, ctx.protected)
        except RequestTooLarge:
            if batch_size > 1:
                batch_size -= 1
                continue
            counts[_retry_or_fail(ctx, ready[0], "request_too_large")] += 1
            queue = queue[1:]
            continue
        except AIUnavailable as error:
            stopped = str(error)
            break
        except AIError as error:
            for row in ready:
                counts[_retry_or_fail(ctx, row, f"ai:{error}")] += 1
            queue = queue[len(batch):]
            continue
        for row, analysis in zip(ready, analyses, strict=True):
            ctx.store.update_source(row["id"], analysis_fields(ctx, analysis))
            counts["analyzed"] += 1
        queue = queue[len(batch):]
    unprocessed = [row for row in queue if row["id"] not in broken]
    for row in unprocessed:
        _release(ctx, row)
    counts["released"] += len(unprocessed)
    if reclaimed:
        counts["reclaimed"] = reclaimed
    return {**counts, **({"stopped": stopped} if stopped else {})}
