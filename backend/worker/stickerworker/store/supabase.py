"""Supabase implementation of Store over PostgREST + Storage (service role, httpx)."""
from __future__ import annotations

import sys
import time
from collections.abc import Callable, Sequence
from datetime import datetime, timedelta
from typing import Any
from urllib.parse import quote

import httpx

from .. import vocab
from ..timeutil import iso, parse_iso, utcnow
from .base import Row, StoreError

MAX_DOWNLOAD_BYTES = 12_000_000
_RETRY_STATUSES = {500, 502, 503, 504}


def _in(values: Sequence[str]) -> str:
    return "in.(" + ",".join('"' + str(v).replace('"', "") + '"' for v in values) + ")"


def _batches(values: Sequence[str], size: int) -> list[Sequence[str]]:
    return [values[i:i + size] for i in range(0, len(values), size)]


def _safe_detail(response: httpx.Response) -> str:
    try:
        body = response.json()
    except ValueError:
        return ""
    if not isinstance(body, dict):
        return ""
    parts = [str(body.get(k, "")) for k in ("code", "error", "message") if body.get(k)]
    return " ".join(parts)[:200]


class SupabaseStore:
    kind = "supabase"

    def __init__(self, url: str, key: str, *, transport: httpx.BaseTransport | None = None,
                 timeout: float = 30.0, sleep: Callable[[float], None] = time.sleep) -> None:
        base = url.rstrip("/")
        if not (base.startswith("https://") or base.startswith(("http://localhost", "http://127.0.0.1"))):
            raise StoreError("SUPABASE_URL must be an https:// URL")
        if not key:
            raise StoreError("SUPABASE_SERVICE_ROLE_KEY is not set")
        self._http = httpx.Client(base_url=base, timeout=timeout, transport=transport, headers={
            "apikey": key, "Authorization": f"Bearer {key}", "User-Agent": "stickerworker/0.1"})
        self._sleep = sleep

    def close(self) -> None:
        self._http.close()

    # ------------------------------------------------------------ transport
    def _call(self, method: str, path: str, *, params: dict[str, str] | None = None, json_body: Any = None,
              content: bytes | None = None, headers: dict[str, str] | None = None) -> httpx.Response:
        for attempt in range(3):
            try:
                response = self._http.request(method, path, params=params, json=json_body,
                                              content=content, headers=headers)
            except httpx.TransportError as error:
                if attempt == 2:
                    raise StoreError(f"{method} {path}: {type(error).__name__}") from None
                self._sleep(1.5 * (attempt + 1))
                continue
            if response.status_code < 300:
                return response
            if response.status_code in _RETRY_STATUSES and attempt < 2:
                self._sleep(1.5 * (attempt + 1))
                continue
            raise StoreError(f"{method} {path} -> HTTP {response.status_code} {_safe_detail(response)}".strip())
        raise StoreError(f"{method} {path}: retries exhausted")

    def _select(self, table: str, params: dict[str, str]) -> list[Row]:
        data = self._call("GET", f"/rest/v1/{table}", params=params).json()
        return data if isinstance(data, list) else []

    def _select_all(self, table: str, params: dict[str, str], cap: int = 20_000, page: int = 1000) -> list[Row]:
        rows: list[Row] = []
        while len(rows) < cap:
            chunk = self._select(table, {**params, "limit": str(min(page, cap - len(rows))),
                                         "offset": str(len(rows))})
            rows.extend(chunk)
            if len(chunk) < page:
                break
        return rows

    def _count(self, table: str, params: dict[str, str]) -> int:
        response = self._call("GET", f"/rest/v1/{table}", params={"select": "id", **params, "limit": "1"},
                              headers={"Prefer": "count=exact"})
        total = response.headers.get("content-range", "").rsplit("/", 1)[-1]
        return int(total) if total.isdigit() else len(response.json())

    def _insert(self, table: str, rows: Sequence[Row], *, returning: bool = True, on_conflict: str | None = None,
                ignore_duplicates: bool = False, merge: bool = False) -> list[Row]:
        prefer = ["return=representation" if returning else "return=minimal"]
        if ignore_duplicates:
            prefer.append("resolution=ignore-duplicates")
        if merge:
            prefer.append("resolution=merge-duplicates")
        response = self._call("POST", f"/rest/v1/{table}", params={"on_conflict": on_conflict} if on_conflict else None,
                              json_body=list(rows), headers={"Prefer": ",".join(prefer)})
        return response.json() if returning and response.content else []

    def _update(self, table: str, filters: dict[str, str], fields: Row, returning: bool = False) -> list[Row]:
        response = self._call("PATCH", f"/rest/v1/{table}", params=filters, json_body=fields,
                              headers={"Prefer": "return=representation" if returning else "return=minimal"})
        return response.json() if returning and response.content else []

    # ------------------------------------------------------------ reference data
    def load_settings(self) -> dict[str, Any]:
        return {row["key"]: row["value"] for row in self._select("settings", {"select": "key,value"})}

    def protected_people(self) -> list[str]:
        return [row["name"] for row in self._select("protected_people", {"select": "name"}) if row.get("name")]

    def takedowns(self) -> list[Row]:
        return self._select_all("takedowns", {"select": "sha256,phash,source_url"})

    # ------------------------------------------------------------ sources
    def _queue_filters(self, status: str, by_score: bool) -> dict[str, str]:
        order = "prescreen_score.desc.nullslast,collected_at.asc" if by_score else "collected_at.asc"
        return {"select": "*", "status": f"eq.{status}", "attempts": "lt.5",
                "or": f'(locked_until.is.null,locked_until.lt."{iso(utcnow())}")', "order": order}

    def claim_sources(self, status: str, next_status: str, limit: int, lease_seconds: int,
                      by_score: bool = False) -> list[Row]:
        if not by_score:
            rows = self._call("POST", "/rest/v1/rpc/claim_sources", json_body={
                "p_status": status, "p_next": next_status, "p_limit": limit,
                "p_lease_seconds": lease_seconds}).json()
            return rows if isinstance(rows, list) else []
        # claim_sources orders by collected_at; this optimistic claim honours prescreen_score.
        claimed: list[Row] = []
        lease = iso(utcnow() + timedelta(seconds=lease_seconds))
        for row in self.peek_sources(status, limit, by_score=True):
            updated = self._update("source_images", {
                "id": f"eq.{row['id']}", "status": f"eq.{status}", "attempts": f"eq.{row['attempts']}"},
                {"status": next_status, "locked_until": lease, "attempts": row["attempts"] + 1}, returning=True)
            claimed.extend(updated[:1])
        return claimed

    def peek_sources(self, status: str, limit: int, by_score: bool = False) -> list[Row]:
        return self._select("source_images", {**self._queue_filters(status, by_score), "limit": str(limit)})

    def update_source(self, source_id: str, fields: Row) -> None:
        self._update("source_images", {"id": f"eq.{source_id}"}, fields)

    def insert_source(self, row: Row) -> Row | None:
        rows = self._insert("source_images", [row], on_conflict="sha256", ignore_duplicates=True)
        return rows[0] if rows else None

    def existing_sha256(self, hashes: Sequence[str]) -> set[str]:
        found: set[str] = set()
        for batch in _batches(list(hashes), 50):
            found |= {r["sha256"] for r in self._select("source_images", {"select": "sha256", "sha256": _in(batch)})}
        return found

    def fingerprints(self, limit: int) -> list[tuple[str, str]]:
        sources = self._select("source_images", {
            "select": "id,phash", "phash": "not.is.null", "status": "not.in.(rejected,failed)",
            "order": "collected_at.desc", "limit": str(limit)})
        stickers = self._select("stickers", {
            "select": "id,phash", "phash": "not.is.null", "status": "neq.rejected",
            "order": "created_at.desc", "limit": str(limit)})
        return [(f"source:{r['id']}", r["phash"]) for r in sources] + \
               [(f"sticker:{r['id']}", r["phash"]) for r in stickers]

    def pending_counts(self) -> dict[str, int]:
        return {status: self._count("source_images", {"status": f"eq.{status}", "attempts": "lt.5"})
                for status in ("new", "screened", "analyzed")}

    # ------------------------------------------------------------ files
    def download(self, bucket: str, path: str) -> bytes:
        data = self._call("GET", f"/storage/v1/object/{bucket}/{quote(path, safe='/')}").content
        if len(data) > MAX_DOWNLOAD_BYTES:
            raise StoreError("download exceeds size limit")
        return data

    def upload(self, bucket: str, path: str, data: bytes, content_type: str, cache_seconds: int = 3600) -> None:
        self._call("POST", f"/storage/v1/object/{bucket}/{quote(path, safe='/')}", content=data, headers={
            "Content-Type": content_type, "x-upsert": "true", "cache-control": f"max-age={cache_seconds}"})

    # ------------------------------------------------------------ stickers
    def insert_sticker(self, row: Row) -> Row:
        rows = self._insert("stickers", [row])
        if not rows:
            raise StoreError("sticker insert returned no row")
        return rows[0]

    def count_stickers_since(self, since: datetime) -> int:
        return self._count("stickers", {"created_at": f"gte.{iso(since)}"})

    def published_stickers(self, limit: int = 5000) -> list[Row]:
        return self._select_all("stickers", {
            "select": "id,kind,rating,category,quality,caption,tags,description,trend_id,file_path,"
                      "published_at,created_at,status",
            "status": "eq.published", "order": "published_at.desc,id.desc"}, cap=limit)

    def stickers_by_id(self, ids: Sequence[str]) -> list[Row]:
        rows: list[Row] = []
        for batch in _batches(list(ids), 50):
            rows += self._select("stickers", {"select": "id,status,quality,kind,rating,category,file_path",
                                              "id": _in(batch)})
        return rows

    # ------------------------------------------------------------ packs
    def packs(self) -> list[Row]:
        return self._select_all("packs", {"select": "*", "order": "created_at.asc"})

    def pack_items(self, pack_ids: Sequence[str]) -> list[Row]:
        rows: list[Row] = []
        for batch in _batches(list(pack_ids), 40):
            rows += self._select_all("pack_items", {"select": "pack_id,sticker_id,position", "pack_id": _in(batch)})
        return rows

    def insert_pack(self, row: Row) -> Row:
        rows = self._insert("packs", [row])
        if not rows:
            raise StoreError("pack insert returned no row")
        return rows[0]

    def update_pack(self, pack_id: str, fields: Row) -> None:
        self._update("packs", {"id": f"eq.{pack_id}"}, fields)

    def add_pack_items(self, rows: Sequence[Row]) -> None:
        if rows:
            self._insert("pack_items", rows, returning=False, on_conflict="pack_id,sticker_id",
                         ignore_duplicates=True)

    def remove_pack_items(self, pack_id: str, sticker_ids: Sequence[str]) -> None:
        for batch in _batches(list(sticker_ids), 50):
            self._call("DELETE", "/rest/v1/pack_items", params={"pack_id": f"eq.{pack_id}", "sticker_id": _in(batch)})

    # ------------------------------------------------------------ trends
    def upsert_trends(self, rows: Sequence[Row]) -> int:
        if rows:
            self._insert("trends", rows, returning=False, on_conflict="day,title", merge=True)
        return len(rows)

    def active_trends(self, since: datetime) -> list[Row]:
        return self._select("trends", {"select": "*", "active": "is.true",
                                       "or": f'(created_at.gte."{iso(since)}",source.eq.owner)',
                                       "order": "score.desc", "limit": "60"})

    def deactivate_trends(self, before: datetime, sources: Sequence[str]) -> int:
        rows = self._update("trends", {"active": "is.true", "created_at": f"lt.{iso(before)}",
                                       "source": _in(sources)}, {"active": False}, returning=True)
        return len(rows)

    # ------------------------------------------------------------ ledger
    def log_ai_call(self, row: Row) -> None:
        try:
            self._insert("ai_calls", [row], returning=False)
        except StoreError as error:  # the ledger must not take the pipeline down
            print(f"warning: ai_calls insert failed: {error}", file=sys.stderr)

    def ai_usage_since(self, since: datetime) -> dict[str, int]:
        stamp = iso(since)
        vision = self._count("source_images", {"analyzed_at": f"gte.{stamp}"}) + self._count("ai_calls", {
            "purpose": f"eq.{vocab.VISION_PURPOSE}", "ok": "is.false", "status": "eq.200", "at": f"gte.{stamp}"})
        text = self._count("ai_calls", {"purpose": _in(vocab.TEXT_PURPOSES), "status": "eq.200",
                                        "at": f"gte.{stamp}"})
        return {"vision": vision, "text": text}

    def start_run(self, stage: str) -> int | None:
        rows = self._insert("worker_runs", [{"stage": stage}])
        return rows[0]["id"] if rows else None

    def finish_run(self, run_id: int | None, ok: bool, summary: Row) -> None:
        if run_id is not None:
            self._update("worker_runs", {"id": f"eq.{run_id}"},
                         {"finished_at": iso(utcnow()), "ok": ok, "summary": summary})

    def last_run_at(self, stage: str) -> datetime | None:
        rows = self._select("worker_runs", {"select": "started_at", "stage": f"eq.{stage}", "ok": "is.true",
                                            "order": "started_at.desc", "limit": "1"})
        return parse_iso(rows[0]["started_at"]) if rows else None
