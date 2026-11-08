"""Runs stages in order, records each in `worker_runs`, and never lets one stage crash the rest."""
from __future__ import annotations

import time
import traceback
from collections.abc import Callable
from datetime import timedelta
from typing import Any

from ..store.base import StoreError
from . import analyze, create, library, packs, prescreen, trends
from .context import Context

STAGES = ("trends", "library", "prescreen", "analyze", "create", "compose")
QUEUE_STAGES = ("prescreen", "analyze", "create")
DEFAULT_LIMITS = {"prescreen": 50, "analyze": 6, "create": 20}
LOCAL_LIMITS = {"prescreen": 1000, "analyze": 1000, "create": 1000}
CHUNK = 100  # claim_sources caps a claim at 100 rows
TRENDS_EVERY = timedelta(hours=3)

QueueStage = Callable[[Context, int], dict[str, Any]]
QUEUE_FUNCS: dict[str, QueueStage] = {"prescreen": prescreen.run, "analyze": analyze.run, "create": create.run}


def _merge(total: dict[str, Any], part: dict[str, Any]) -> None:
    for key, value in part.items():
        if isinstance(value, int) and isinstance(total.get(key, 0), int):
            total[key] = total.get(key, 0) + value
        else:
            total[key] = value


def run_queue(ctx: Context, name: str, limit: int) -> dict[str, Any]:
    """Repeat a queue stage in claim-sized chunks until the limit or the queue runs out."""
    total: dict[str, Any] = {}
    remaining = limit
    while remaining > 0:
        chunk = min(CHUNK, remaining)
        part = QUEUE_FUNCS[name](ctx, chunk)
        _merge(total, part)
        claimed = part.get("claimed", 0)
        if ctx.dry_run or "skipped" in part or "stopped" in part or claimed < chunk:
            break
        remaining -= claimed
    return total


def trends_due(ctx: Context) -> bool:
    last = ctx.store.last_run_at("trends")
    return last is None or ctx.now() - last >= TRENDS_EVERY


def _safe_start_run(ctx: Context, name: str) -> int | None:
    # The run ledger is observability, not the pipeline: a StoreError here must not abort the stage (P4).
    try:
        return ctx.store.start_run(name)
    except StoreError:
        return None


def _safe_finish_run(ctx: Context, run_id: int | None, ok: bool, summary: dict[str, Any]) -> None:
    try:
        ctx.store.finish_run(run_id, ok, summary)
    except StoreError:
        pass  # losing a ledger row is survivable; killing the remaining stages is not


def run_stages(ctx: Context, stage: str, limit: int | None = None, *, offline: bool = False,
               local: bool = False) -> tuple[bool, dict[str, Any]]:
    names = STAGES if stage == "all" else (stage,)
    limits = LOCAL_LIMITS if local else DEFAULT_LIMITS
    results: dict[str, Any] = {}
    all_ok = True
    for name in names:
        if name == "trends" and stage == "all" and not offline:
            try:
                due = trends_due(ctx)
            except StoreError:
                due = True  # can't read the last run; attempt trends anyway (its own try records any failure)
            if not due:
                results[name] = {"ok": True, "skipped": "fetched_recently"}
                continue
        run_id = _safe_start_run(ctx, name)
        started = time.monotonic()
        try:
            if name == "trends":
                summary = trends.run(ctx, offline=offline)
            elif name == "library":
                summary = library.run(ctx)
            elif name == "compose":
                summary = packs.run(ctx)
            else:
                summary = run_queue(ctx, name, limit or limits[name])
            ok = True
        except Exception as error:  # noqa: BLE001 - record and continue with the next stage
            traceback.print_exc()
            summary, ok = {"error": f"{type(error).__name__}: {error}"[:500]}, False
        summary["seconds"] = round(time.monotonic() - started, 2)
        _safe_finish_run(ctx, run_id, ok, summary)
        results[name] = {"ok": ok, **summary}
        all_ok = all_ok and ok
    return all_ok, results
