"""Daily collection plan for HandNavigate, the owner's image collector.

Queues `collection_jobs` up to settings.collect.daily_images per IST day. Topics come in this order: the owner's
topics, then fresh trends (as meme searches), then evergreen sticker searches. HandNavigate claims the jobs through
the intake function (outbound polling from the owner's PC), downloads with its own browsers and posts the images
back as `source_images`, which prescreen/analyze/create then turn into stickers.

A topic doesn't repeat within three days, and never names a protected person or carries explicit words or slurs.
"""
from __future__ import annotations

from datetime import timedelta
from typing import Any

from ..lexicon import lexical_flags
from ..store.base import Row
from ..timeutil import ist_day
from .context import Context
from .trends import is_sensitive

# Sticker-friendly searches for days when trends are thin.
EVERGREEN: tuple[str, ...] = (
    "funny reaction meme", "hinglish meme", "office meme india", "relationship meme", "bollywood reaction meme",
    "cute baby reaction", "funny dog reaction", "funny cat reaction", "cricket reaction meme", "exam meme india",
    "chai meme", "monday meme", "desi mom meme", "savage reply meme", "good morning funny",
)
REPEAT_AFTER = timedelta(days=3)
TREND_WINDOW = timedelta(days=2)


def trend_topic(row: Row) -> str | None:
    title = " ".join(str(row.get("title") or "").split())
    if not 3 <= len(title) <= 60:
        return None
    return title if "meme" in title.lower() or row.get("source") == "owner" else f"{title} meme"


def candidates(ctx: Context, used: set[str]) -> list[str]:
    """Topics in priority order, without repeats, recent ones, protected people or unsafe words."""
    protected = [name.lower() for name in ctx.protected if name]
    picked: list[str] = []
    seen: set[str] = set()

    def add(topic: str) -> None:
        key = topic.lower()
        if not topic or key in used or key in seen:
            return
        if lexical_flags(topic) & {"explicit", "slur"} or any(name in key for name in protected):
            return
        seen.add(key)
        picked.append(topic)

    for topic in ctx.settings.collect.owner_topics:
        add(topic)
    for row in ctx.store.active_trends(ctx.now() - TREND_WINDOW):
        if row.get("source") != "owner" and is_sensitive(str(row.get("title") or "")):
            continue
        topic = trend_topic(row)
        if topic:
            add(topic)
    for topic in EVERGREEN:
        add(topic)
    return picked


def run(ctx: Context) -> dict[str, Any]:
    cfg = ctx.settings.collect
    if not cfg.enabled:
        return {"skipped": "collect_disabled"}
    if cfg.daily_images <= 0:
        return {"skipped": "no_daily_images"}
    day = ist_day(ctx.now()).isoformat()
    planned = ctx.store.planned_collection_images(day)
    remaining = cfg.daily_images - planned
    if remaining <= 0:
        return {"queued": 0, "planned_today": planned}
    used = {topic.lower() for topic in ctx.store.recent_collection_topics(ctx.now() - REPEAT_AFTER)}
    pool = candidates(ctx, used)
    jobs: list[Row] = []
    while remaining > 0 and pool:
        topics, pool = pool[:cfg.topics_per_job], pool[cfg.topics_per_job:]
        limit = min(cfg.job_size, remaining)
        jobs.append({"plan_date": day, "topics": topics, "sources": list(cfg.sources), "image_limit": limit,
                     "created_by": "worker", "note": "daily plan"})
        remaining -= limit
    if not jobs:
        return {"queued": 0, "planned_today": planned, "skipped": "no_fresh_topics"}
    ctx.store.insert_collection_jobs(jobs)
    images = sum(job["image_limit"] for job in jobs)
    return {"queued": len(jobs), "images": images, "planned_today": planned + images,
            "topics": [topic for job in jobs for topic in job["topics"]][:20]}
