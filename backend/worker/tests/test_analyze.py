"""Analyze stage: score ordering, batch shrinking on 413, clean release when the AI stops."""
from __future__ import annotations

import hashlib

from conftest import make_analysis
from samples import encode, photo

from stickerworker.ai.base import AIUnavailable
from stickerworker.ai.groq import RequestTooLarge
from stickerworker.pipeline import analyze


class StubAI:
    provider = "stub"

    def __init__(self, *, remaining: int = 100, max_images: int = 3, fail: Exception | None = None) -> None:
        self._remaining = remaining
        self.max_images = max_images
        self.fail = fail
        self.batches: list[list[str]] = []

    def remaining(self, kind):
        return self._remaining

    def images_per_request(self):
        return 3

    def analyze(self, items, categories, protected):
        self.batches.append([item.ref for item in items])
        if self.fail is not None:
            raise self.fail
        if len(items) > self.max_images:
            raise RequestTooLarge("request_too_large", 413)
        return [make_analysis(description=f"analysed {item.ref}") for item in items]


def add_source(store, seed: int, score: float, data: bytes | None = None) -> str:
    data = data if data is not None else encode(photo(seed=seed), "JPEG")
    sha = hashlib.sha256(data + str(seed).encode()).hexdigest()
    path = f"upload/{sha[:2]}/{sha}.jpg"
    store.upload("sources", path, data, "image/jpeg")
    row = store.insert_source({"sha256": sha, "storage_path": path, "mime": "image/jpeg", "bytes": len(data),
                               "site": "upload", "status": "screened", "prescreen_score": score})
    return row["id"]


def test_highest_prescreen_score_goes_first(ctx, store):
    low, high, mid = add_source(store, 1, 2.0), add_source(store, 2, 9.5), add_source(store, 3, 5.0)
    ctx.ai = StubAI()
    summary = analyze.run(ctx, 10)
    assert ctx.ai.batches == [[high, mid, low]]
    assert summary["analyzed"] == 3
    row = store.source(high)
    assert row["status"] == "analyzed" and row["analysis"]["description"] == f"analysed {high}"
    assert row["analyzed_at"] and row["locked_until"] is None


def test_request_too_large_shrinks_the_batch(ctx, store):
    ids = [add_source(store, seed, 5.0) for seed in range(4)]
    ctx.ai = StubAI(max_images=1)
    summary = analyze.run(ctx, 10)
    assert [len(b) for b in ctx.ai.batches] == [3, 2, 1, 1, 1, 1]
    assert summary["analyzed"] == 4
    assert {store.source(i)["status"] for i in ids} == {"analyzed"}


def test_ai_stop_releases_sources_with_attempts_restored(ctx, store):
    ids = [add_source(store, seed, 5.0) for seed in range(3)]
    broken = add_source(store, 99, 9.9, data=b"\xff\xd8\xff not really a jpeg")
    ctx.ai = StubAI(fail=AIUnavailable("daily vision budget exhausted"))
    summary = analyze.run(ctx, 10)
    assert summary["stopped"] == "daily vision budget exhausted" and summary["released"] == 3
    for source_id in ids:
        row = store.source(source_id)
        assert (row["status"], row["attempts"], row["locked_until"]) == ("screened", 0, None)
    assert store.source(broken)["status"] == "failed"


def test_a_killed_run_leaves_rows_in_analyzing_that_the_next_run_reclaims(ctx, store):
    # Simulate a crashed run: a source stuck in 'analyzing' with an expired lease and a bumped attempt counter.
    stuck = add_source(store, 7, 8.0)
    store.update_source(stuck, {"status": "analyzing", "locked_until": "2000-01-01T00:00:00+00:00", "attempts": 1})
    ctx.ai = StubAI()
    summary = analyze.run(ctx, 10)
    assert summary["reclaimed"] == 1
    row = store.source(stuck)
    assert row["status"] == "analyzed" and row["locked_until"] is None  # reclaimed, then analysed


def test_a_fresh_analyzing_lease_is_not_reclaimed(ctx, store):
    live = add_source(store, 8, 8.0)
    store.update_source(live, {"status": "analyzing", "locked_until": "2999-01-01T00:00:00+00:00"})
    ctx.ai = StubAI()
    summary = analyze.run(ctx, 10)
    assert summary.get("reclaimed", 0) == 0 and store.source(live)["status"] == "analyzing"


def test_budget_gates_the_stage(ctx, store):
    add_source(store, 1, 5.0)
    ctx.ai = StubAI(remaining=0)
    assert analyze.run(ctx, 10) == {"skipped": "vision_budget_exhausted"}
    ctx.ai = None
    assert analyze.run(ctx, 10) == {"skipped": "no_ai_provider"}


def _missing_object_source(store, seed: int, score: float) -> str:
    """A screened source whose storage object does not exist: download() raises StoreError inside prepare()."""
    sha = hashlib.sha256(f"missing-{seed}".encode()).hexdigest()
    row = store.insert_source({"sha256": sha, "storage_path": f"upload/{sha[:2]}/{sha}.jpg", "mime": "image/jpeg",
                               "bytes": 10, "site": "upload", "status": "screened", "prescreen_score": score})
    return row["id"]


def test_download_blip_retries_one_row_without_aborting_the_stage(ctx, store):
    # P1: a StoreError downloading one source must retry that row, not crash the whole analyze stage.
    bad = _missing_object_source(store, 50, 9.9)  # highest score -> claimed and prepared first
    good = [add_source(store, seed, 5.0) for seed in (51, 52)]
    ctx.ai = StubAI()
    summary = analyze.run(ctx, 10)
    assert summary["analyzed"] == 2  # the stage kept going past the broken row
    assert {store.source(i)["status"] for i in good} == {"analyzed"}
    row = store.source(bad)
    assert row["status"] == "screened" and row["locked_until"] is None  # back to the queue for a retry
    assert row["last_error"].startswith("prepare:") and row["attempts"] == 1


def test_repeated_download_failures_eventually_fail_the_row(ctx, store):
    bad = _missing_object_source(store, 53, 9.9)
    ctx.ai = StubAI()
    for _ in range(analyze.MAX_ATTEMPTS):
        analyze.run(ctx, 10)
    row = store.source(bad)
    assert row["status"] == "failed" and row["last_error"].startswith("prepare:")


def test_analyze_success_resets_attempts_for_the_create_stage(ctx, store):
    # P2: a successful analyze hands create a fresh attempt budget instead of carrying retries forward.
    sid = add_source(store, 54, 5.0)
    store.update_source(sid, {"attempts": 2})  # simulate earlier retries before this successful run
    ctx.ai = StubAI()
    analyze.run(ctx, 10)
    row = store.source(sid)
    assert row["status"] == "analyzed" and row["attempts"] == 0 and row["last_error"] is None
