"""Create stage lease safety: reclaim crashed leases and release rows when the trends read fails (P2)."""
from __future__ import annotations

import hashlib

import pytest
from samples import encode, photo

from stickerworker.pipeline import create
from stickerworker.store.base import StoreError


def add_analyzed(store, seed: int, *, attempts: int = 0, locked_until: str | None = None) -> str:
    data = encode(photo(seed=seed), "JPEG")
    sha = hashlib.sha256(data + str(seed).encode()).hexdigest()
    path = f"upload/{sha[:2]}/{sha}.jpg"
    store.upload("sources", path, data, "image/jpeg")
    row = store.insert_source({"sha256": sha, "storage_path": path, "mime": "image/jpeg", "bytes": len(data),
                               "site": "upload", "status": "analyzed", "prescreen_score": 6.0,
                               "attempts": attempts, "locked_until": locked_until})
    return row["id"]


def test_create_reclaims_a_crashed_analyzed_lease(ctx, store):
    # A previous create run died mid-row: status analyzed, expired lease, a bumped attempt counter.
    sid = add_analyzed(store, 41, attempts=1, locked_until="2000-01-01T00:00:00+00:00")
    summary = create.run(ctx, 10)
    assert summary["reclaimed"] == 1
    row = store.source(sid)
    # Reclaimed (lease cleared, attempt refunded), then claimed and processed. No stored analysis -> failed fast,
    # but the important part is the row is no longer stranded under an expired lease.
    assert row["locked_until"] is None and row["status"] == "failed"


def test_a_fresh_analyzed_lease_is_not_reclaimed(ctx, store):
    sid = add_analyzed(store, 43, locked_until="2999-01-01T00:00:00+00:00")
    summary = create.run(ctx, 10)
    assert summary.get("reclaimed", 0) == 0
    assert store.source(sid)["status"] == "analyzed"  # still leased by the "other" run, untouched


def test_trends_read_failure_releases_the_rows_it_just_leased(ctx, store, monkeypatch):
    sid = add_analyzed(store, 42)

    def boom(*args, **kwargs):
        raise StoreError("trends read failed")

    monkeypatch.setattr(store, "active_trends", boom)
    with pytest.raises(StoreError):
        create.run(ctx, 10)
    row = store.source(sid)
    # The guard unlocked the row and refunded the attempt the claim had just bumped, so a later run can retry it.
    assert row["status"] == "analyzed" and row["locked_until"] is None and row["attempts"] == 0