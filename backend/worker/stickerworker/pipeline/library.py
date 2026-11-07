"""Pulls HandNavigate's image library into the sticker queue.

HandNavigate (the owner's collector, deployed on a VM) saves verified Pinterest/Reddit images per topic in the same
Supabase project: rows in `collected_images`, bytes in the private `handnavigate-images` bucket. Each run copies the
newest rows that aren't in `source_images` yet into the `sources` bucket and queues them (status `new`), so prescreen,
analyze, create and compose turn them into stickers. Bytes are re-hashed against HandNavigate's record before use.
Without a HandNavigate library in the project the stage simply reports that and does nothing.
"""
from __future__ import annotations

import hashlib
from collections import Counter
from typing import Any

from ..imaging.decode import EXTENSIONS, IMAGE_MIMES, DecodeError, sniff_mime
from ..store.base import SOURCES_BUCKET, StoreError
from ..textutil import clean_text
from .context import Context
from .intake import probe

HAND_BUCKET = "handnavigate-images"
PER_RUN = 200


def topic_hint(row: dict[str, Any]) -> str | None:
    key = str(row.get("topic_key") or "").replace("_", " ").strip()
    return clean_text(key, 120) or None


def run(ctx: Context, limit: int = PER_RUN) -> dict[str, Any]:
    store = ctx.store
    try:
        rows = store.handnavigate_images(limit)
    except StoreError as error:  # no HandNavigate tables in this project (or no access)
        return {"skipped": "no_handnavigate_library", "detail": str(error)[:200]}
    counts: Counter[str] = Counter(seen=len(rows))
    if not rows:
        return dict(counts)
    try:
        # A topic switched off in HandNavigate stops feeding the queue (its images aren't downloaded or analysed).
        # Switching it back on imports them on the next run. Without the topics table, nothing is filtered.
        disabled = store.disabled_collection_topics()
    except StoreError:
        disabled = set()
    existing = store.existing_sha256([r["sha256"] for r in rows])
    for row in rows:
        sha = str(row.get("sha256") or "")
        if sha in existing:
            counts["already_queued"] += 1
            continue
        if row.get("topic_key") in disabled:
            counts["skipped_topic_off"] += 1
            continue
        if ctx.takedowns.match(sha256=sha, url=row.get("source_page_url")):
            counts["skipped_takedown"] += 1
            continue
        try:
            data = store.download(HAND_BUCKET, row["storage_path"])
        except StoreError:
            counts["skipped_missing"] += 1
            continue
        if hashlib.sha256(data).hexdigest() != sha:
            counts["skipped_changed"] += 1
            continue
        mime = sniff_mime(data)
        if mime not in IMAGE_MIMES:
            counts["skipped_unsupported"] += 1
            continue
        try:
            info = probe(data, mime)
        except DecodeError:
            counts["skipped_invalid"] += 1
            continue
        site = str(row.get("source") or "handnavigate")
        storage_path = f"{site}/{sha[:2]}/{sha}.{EXTENSIONS[mime]}"
        if ctx.dry_run:
            counts["planned"] += 1
            continue
        store.upload(SOURCES_BUCKET, storage_path, data, mime)
        inserted = store.insert_source({
            "sha256": sha, "storage_path": storage_path, "mime": mime, "bytes": len(data), "site": site,
            "source_url": row.get("source_image_url"), "page_url": row.get("source_page_url"),
            "license": row.get("rights_status"), "collector": "handnavigate", "topic_hint": topic_hint(row), **info})
        counts["queued" if inserted else "already_queued"] += 1
        existing.add(sha)
    return dict(counts)
