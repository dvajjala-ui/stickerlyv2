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


def test_budget_gates_the_stage(ctx, store):
    add_source(store, 1, 5.0)
    ctx.ai = StubAI(remaining=0)
    assert analyze.run(ctx, 10) == {"skipped": "vision_budget_exhausted"}
    ctx.ai = None
    assert analyze.run(ctx, 10) == {"skipped": "no_ai_provider"}
