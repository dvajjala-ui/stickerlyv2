"""Stage 1 (free, every `new` source): decode, quality metrics, pHash, duplicates, takedowns, OCR.

new -> screened | rejected | taken_down | failed (or back to new for a retry).
"""
from __future__ import annotations

import hashlib
from collections import Counter
from typing import Any

from ..imaging import clip, ocr
from ..imaging.decode import VIDEO_MIMES, DecodeError, decode_image, sniff_mime
from ..imaging.metrics import measure, phash_hex, prescreen_score
from ..store.base import SOURCES_BUCKET, Row, StoreError
from .context import Context
from .fingerprints import nearest

LEASE_SECONDS = 600
FINGERPRINT_WINDOW = 5000
MIN_SIDE = 150
MAX_ASPECT = 3.5
MIN_CONTRAST = 6.0
MIN_SCORE = 2.5
MAX_ATTEMPTS = 3


TEXT_BONUS_MAX = 2.0


def text_bonus(text: str | None) -> float:
    """Images with words on them (captioned reactions, memes) are far more sticker-worthy than plain scenery.
    Return a 0-2 bump to the prescreen score so they rank ahead for the limited vision budget. 0 when no real text."""
    if not text:
        return 0.0
    words = [w for w in text.split() if len(w) >= 2]
    if not words:
        return 0.0
    return round(min(TEXT_BONUS_MAX, 0.6 + 0.2 * len(words)), 2)


def quality_problem(metrics: dict[str, float], score: float) -> str | None:
    if metrics["short_side"] < MIN_SIDE:
        return "too_small"
    if metrics["aspect"] > MAX_ASPECT:
        return "extreme_aspect"
    if metrics["contrast"] < MIN_CONTRAST or metrics["coverage"] < 0.05:
        return "blank"
    if score < MIN_SCORE:
        return "low_quality"
    return None


def screen(ctx: Context, row: Row, known: list[tuple[str, str]]) -> dict[str, Any]:
    takedown = ctx.takedowns.match(sha256=row["sha256"], url=row.get("source_url"))
    if takedown:
        return {"status": "taken_down", "last_error": takedown}
    data = ctx.store.download(SOURCES_BUCKET, row["storage_path"])
    mime = sniff_mime(data) or row.get("mime", "")
    fields: dict[str, Any] = {}
    if mime in VIDEO_MIMES:
        image = clip.poster(data, mime)
    else:
        decoded = decode_image(data)
        image = decoded.image
        fields.update(frames=decoded.frames, duration_ms=decoded.duration_ms)
    metrics = measure(image)
    score = prescreen_score(metrics)
    phash = phash_hex(image)
    fields.update(phash=phash, width=image.width, height=image.height, prescreen_score=score,
                  prescreen={**metrics, "sha256_ok": hashlib.sha256(data).hexdigest() == row["sha256"],
                             "mime": mime})
    takedown = ctx.takedowns.match(phash=phash)
    if takedown:
        return {**fields, "status": "taken_down", "last_error": takedown}
    duplicate = nearest(phash, known, exclude=f"source:{row['id']}")
    if duplicate:
        return {**fields, "status": "rejected", "last_error": f"duplicate_of:{duplicate[0]}:{duplicate[1]}"}
    problem = quality_problem(metrics, score)
    if problem:
        return {**fields, "status": "rejected", "last_error": problem}
    text = ocr.read_text(image)
    if text is not None:
        fields["ocr_text"] = text
    bonus = text_bonus(text)
    if bonus:
        # Ranking only: the quality gate above already ran on the raw score, so this never admits a bad image.
        fields["prescreen_score"] = round(min(10.0, score + bonus), 2)
        fields["prescreen"]["text_bonus"] = bonus
    return {**fields, "status": "screened", "last_error": None}


def run(ctx: Context, limit: int) -> dict[str, Any]:
    rows = ctx.store.claim_sources("new", "new", limit, LEASE_SECONDS)
    known = list(ctx.store.fingerprints(FINGERPRINT_WINDOW)) if rows else []
    counts: Counter[str] = Counter()
    for row in rows:
        try:
            fields = screen(ctx, row, known)
        except DecodeError as error:
            fields = {"status": "rejected", "last_error": str(error)}
        except clip.ClipError as error:
            fields = {"status": "failed", "last_error": str(error)}
        except Exception as error:  # noqa: BLE001 - one bad file must not stall the queue
            retry = row.get("attempts", 0) < MAX_ATTEMPTS
            kind = "store" if isinstance(error, StoreError) else type(error).__name__
            fields = {"status": "new" if retry else "failed", "last_error": f"{kind}:{error}"[:300]}
        ctx.store.update_source(row["id"], {**fields, "locked_until": None})
        counts[fields["status"]] += 1
        if fields["status"] == "screened":
            known.insert(0, (f"source:{row['id']}", fields["phash"]))  # catch duplicates within this batch
    return {"claimed": len(rows), **counts}
