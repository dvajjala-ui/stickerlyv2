"""Runner: the run ledger is observability, not the pipeline. A StoreError there must not abort stages (P4)."""
from __future__ import annotations

from stickerworker.pipeline import runner
from stickerworker.pipeline import trends as trends_mod
from stickerworker.store.base import StoreError


def test_ledger_write_errors_do_not_abort_the_pipeline(ctx, store, monkeypatch):
    def boom(*args, **kwargs):
        raise StoreError("ledger unavailable")

    monkeypatch.setattr(store, "start_run", boom)
    monkeypatch.setattr(store, "finish_run", boom)
    ok, results = runner.run_stages(ctx, "all", offline=True)
    # Every stage still ran and was recorded; losing ledger rows did not kill the remaining stages.
    assert set(results) == set(runner.STAGES)
    assert ok and all(r["ok"] for r in results.values())


def test_trends_due_read_error_still_attempts_trends(ctx, store, monkeypatch):
    def boom(*args, **kwargs):
        raise StoreError("cannot read last run")

    monkeypatch.setattr(store, "last_run_at", boom)
    # Avoid the network: the only online stage (trends) is stubbed. offline=False so the due-check actually runs.
    monkeypatch.setattr(trends_mod, "run", lambda ctx, offline=False: {"marker": "trends_ran"})
    ok, results = runner.run_stages(ctx, "all", offline=False)
    # due=True fallback ran trends instead of skipping it as "fetched_recently".
    assert results["trends"].get("marker") == "trends_ran"
    assert results["trends"]["ok"] and "skipped" not in results["trends"]