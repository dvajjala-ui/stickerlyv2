"""Offline Store: SQLite + files under one directory, mirroring the Supabase contract.

Used by `run --local DIR` and the test suite. Emulates the relevant triggers
(sticker search text/published_at, pack_items -> sticker_count/version) and the
claim_sources / take_down RPCs.
"""
from __future__ import annotations

import json
import sqlite3
import uuid
from collections.abc import Callable, Sequence
from datetime import datetime, timedelta
from pathlib import Path, PurePosixPath
from typing import Any

from .. import vocab
from ..settings import DEFAULT_PROTECTED_PEOPLE, DEFAULT_ROWS
from ..timeutil import iso, ist_day, parse_iso, utcnow
from .base import Row, StoreError

SCHEMA = """
create table if not exists settings (key text primary key, value text not null);
create table if not exists protected_people (name text primary key, note text);
create table if not exists takedowns (id text primary key, sha256 text, phash text, source_url text,
  reason text not null, report_id text, created_at text not null);
create table if not exists source_images (
  id text primary key, sha256 text not null unique, phash text, storage_path text not null unique,
  mime text not null, width integer, height integer, bytes integer not null, frames integer not null default 1,
  duration_ms integer not null default 0, site text not null, source_url text, page_url text, author text,
  license text, collector text not null default 'unknown', topic_hint text, collected_at text not null,
  status text not null default 'new', attempts integer not null default 0, locked_until text, last_error text,
  prescreen text, prescreen_score real, ocr_text text, analysis text, kind text, has_text integer,
  language text, category text, mood text not null default '[]', tags text not null default '[]',
  emojis text not null default '[]', description text, nsfw text, minors integer, real_person integer,
  public_figure text, sticker_score integer, analyzed_at text, created_at text not null, updated_at text not null);
create table if not exists stickers (
  id text primary key, source_id text, recipe text not null, kind text not null, rating text not null,
  category text not null, caption text, language text, tags text not null default '[]',
  emojis text not null default '[]', description text, file_path text not null unique,
  sha256 text not null unique, phash text, bytes integer not null, ai_generated integer not null default 1,
  credit text, quality integer, trend_id text, status text not null default 'draft', review_note text,
  quality_score integer, quality_reasons text not null default '[]', quality_signals text, render_phash text,
  search_text text, published_at text, created_at text not null, updated_at text not null);
create table if not exists packs (
  id text primary key, slug text unique, title text not null, description text, kind text not null,
  rating text not null, category text, pack_type text not null, series text, volume integer not null default 1,
  cover_sticker_id text, version integer not null default 1, sticker_count integer not null default 0,
  status text not null default 'draft', published_at text, created_at text not null, updated_at text not null,
  unique (series, kind, rating, volume));
create table if not exists pack_items (pack_id text not null, sticker_id text not null, position integer not null,
  added_at text not null, primary key (pack_id, sticker_id));
create table if not exists trends (
  id text primary key, day text not null, title text not null, summary text, keywords text not null default '[]',
  category text, source text not null default 'owner', source_urls text not null default '[]',
  score real not null default 0, active integer not null default 1, created_at text not null, unique (day, title));
create table if not exists ai_calls (id integer primary key autoincrement, at text not null, provider text not null,
  model text not null, key_slot integer, purpose text not null, ok integer not null, status integer,
  input_tokens integer, output_tokens integer, latency_ms integer, error text);
create table if not exists collected_images (id text primary key, source text not null, topic_key text not null,
  source_page_url text not null, source_image_url text not null, storage_path text not null unique,
  sha256 text not null unique, mime_type text not null, rights_status text not null default 'reference_only',
  collected_at text not null);
create table if not exists worker_runs (id integer primary key autoincrement, started_at text not null,
  finished_at text, stage text not null, ok integer, summary text);
"""

# Columns added after the first release, applied to existing local databases (additive, like the SQL migrations).
ADDED_COLUMNS: dict[str, dict[str, str]] = {
    "stickers": {"quality_score": "integer", "quality_reasons": "text not null default '[]'",
                 "quality_signals": "text", "render_phash": "text"},
}

JSON_COLUMNS: dict[str, frozenset[str]] = {
    "settings": frozenset({"value"}),
    "source_images": frozenset({"prescreen", "analysis", "mood", "tags", "emojis"}),
    "stickers": frozenset({"tags", "emojis", "credit", "quality_reasons", "quality_signals"}),
    "trends": frozenset({"keywords", "source_urls"}),
    "worker_runs": frozenset({"summary"}),
}
BOOL_COLUMNS: dict[str, frozenset[str]] = {
    "source_images": frozenset({"has_text", "minors", "real_person"}),
    "stickers": frozenset({"ai_generated"}),
    "trends": frozenset({"active"}),
    "ai_calls": frozenset({"ok"}),
    "worker_runs": frozenset({"ok"}),
}


class LocalStore:
    kind = "local"

    def __init__(self, root: Path | str, now: Callable[[], datetime] = utcnow) -> None:
        self.root = Path(root)
        self.files = self.root / "storage"
        self.root.mkdir(parents=True, exist_ok=True)
        self._now = now
        self.db = sqlite3.connect(self.root / "local.sqlite3")
        self.db.row_factory = sqlite3.Row
        self.db.executescript(SCHEMA)
        self._migrate()
        self._seed()

    def close(self) -> None:
        self.db.close()

    # ------------------------------------------------------------ helpers
    def _stamp(self) -> str:
        return iso(self._now())

    @staticmethod
    def _encode(table: str, row: Row) -> Row:
        out: Row = {}
        for key, value in row.items():
            if key in JSON_COLUMNS.get(table, ()) and value is not None:
                value = json.dumps(value, ensure_ascii=False)
            elif isinstance(value, bool):
                value = int(value)
            out[key] = value
        return out

    @staticmethod
    def _decode(table: str, record: sqlite3.Row) -> Row:
        row = dict(record)
        for key in JSON_COLUMNS.get(table, ()):
            if row.get(key) is not None:
                row[key] = json.loads(row[key])
        for key in BOOL_COLUMNS.get(table, ()):
            if row.get(key) is not None:
                row[key] = bool(row[key])
        return row

    def _select(self, table: str, sql: str, params: Sequence[Any] = ()) -> list[Row]:
        return [self._decode(table, r) for r in self.db.execute(sql, tuple(params)).fetchall()]

    def _insert(self, table: str, row: Row, *, ignore: bool = False) -> bool:
        data = self._encode(table, row)
        columns = ", ".join(data)
        marks = ", ".join("?" for _ in data)
        verb = "insert or ignore" if ignore else "insert"
        try:
            with self.db:
                cursor = self.db.execute(f"{verb} into {table} ({columns}) values ({marks})", tuple(data.values()))
        except sqlite3.IntegrityError as error:
            raise StoreError(f"{table} insert conflict: {error}") from None
        return cursor.rowcount > 0

    def _update(self, table: str, key: str, value: Any, fields: Row) -> int:
        if not fields:
            return 0
        data = self._encode(table, fields)
        assignments = ", ".join(f"{column} = ?" for column in data)
        with self.db:
            cursor = self.db.execute(f"update {table} set {assignments} where {key} = ?", (*data.values(), value))
        return cursor.rowcount

    def _migrate(self) -> None:
        for table, columns in ADDED_COLUMNS.items():
            have = {r[1] for r in self.db.execute(f"pragma table_info({table})")}
            with self.db:
                for column, declaration in columns.items():
                    if column not in have:
                        self.db.execute(f"alter table {table} add column {column} {declaration}")

    def _seed(self) -> None:
        for key, value in DEFAULT_ROWS.items():  # like the migrations: add missing keys, keep owner edits
            self._insert("settings", {"key": key, "value": value}, ignore=True)
        if not self.db.execute("select 1 from protected_people limit 1").fetchone():
            for name, note in DEFAULT_PROTECTED_PEOPLE:
                self._insert("protected_people", {"name": name, "note": note})

    def _file(self, bucket: str, path: str) -> Path:
        parts = PurePosixPath(path).parts
        if not parts or any(p in ("..", "") or ":" in p for p in parts) or path.startswith("/"):
            raise StoreError("unsafe storage path")
        return self.files.joinpath(bucket, *parts)

    # ------------------------------------------------------------ reference data
    def load_settings(self) -> dict[str, Any]:
        return {r["key"]: r["value"] for r in self._select("settings", "select key, value from settings")}

    def set_setting(self, key: str, value: Any) -> None:
        with self.db:
            self.db.execute("insert into settings (key, value) values (?, ?) on conflict(key) do update set "
                            "value = excluded.value", (key, json.dumps(value)))

    def protected_people(self) -> list[str]:
        return [r["name"] for r in self.db.execute("select name from protected_people order by name")]

    def takedowns(self) -> list[Row]:
        return self._select("takedowns", "select sha256, phash, source_url from takedowns")

    # ------------------------------------------------------------ sources
    def _queue_sql(self, by_score: bool) -> str:
        order = "prescreen_score desc nulls last, collected_at asc" if by_score else "collected_at asc"
        return ("select * from source_images where status = ? and attempts < 5 and "
                f"(locked_until is null or locked_until < ?) order by {order} limit ?")

    def peek_sources(self, status: str, limit: int, by_score: bool = False) -> list[Row]:
        return self._select("source_images", self._queue_sql(by_score), (status, self._stamp(), max(1, limit)))

    def claim_sources(self, status: str, next_status: str, limit: int, lease_seconds: int,
                      by_score: bool = False) -> list[Row]:
        lease = iso(self._now() + timedelta(seconds=lease_seconds))
        with self.db:
            ids = [r["id"] for r in self.db.execute(self._queue_sql(by_score),
                                                     (status, self._stamp(), max(1, min(limit, 100))))]
            for source_id in ids:
                self.db.execute("update source_images set status = ?, locked_until = ?, attempts = attempts + 1, "
                                "updated_at = ? where id = ?", (next_status, lease, self._stamp(), source_id))
        return [self.source(i) for i in ids]

    def source(self, source_id: str) -> Row:
        rows = self._select("source_images", "select * from source_images where id = ?", (source_id,))
        if not rows:
            raise StoreError("no such source")
        return rows[0]

    def update_source(self, source_id: str, fields: Row) -> None:
        self._update("source_images", "id", source_id, {**fields, "updated_at": self._stamp()})

    def insert_source(self, row: Row) -> Row | None:
        stamp = self._stamp()
        full = {"id": str(uuid.uuid4()), "collected_at": stamp, "created_at": stamp, "updated_at": stamp,
                "status": "new", "attempts": 0, "frames": 1, "duration_ms": 0, "collector": "unknown",
                "mood": [], "tags": [], "emojis": [], **row}
        if not self._insert("source_images", full, ignore=True):
            return None
        return self.source(full["id"])

    def existing_sha256(self, hashes: Sequence[str]) -> set[str]:
        found: set[str] = set()
        values = list(hashes)
        for i in range(0, len(values), 200):
            batch = values[i:i + 200]
            marks = ",".join("?" for _ in batch)
            found |= {r[0] for r in self.db.execute(f"select sha256 from source_images where sha256 in ({marks})",
                                                    batch)}
        return found

    def fingerprints(self, limit: int) -> list[tuple[str, str]]:
        sources = self.db.execute("select id, phash from source_images where phash is not null and status not in "
                                  "('rejected', 'failed') order by collected_at desc limit ?", (limit,)).fetchall()
        stickers = self.db.execute("select id, phash from stickers where phash is not null and status != 'rejected' "
                                   "order by created_at desc limit ?", (limit,)).fetchall()
        return [(f"source:{r[0]}", r[1]) for r in sources] + [(f"sticker:{r[0]}", r[1]) for r in stickers]

    def pending_counts(self) -> dict[str, int]:
        return {status: self.db.execute("select count(*) from source_images where status = ? and attempts < 5",
                                        (status,)).fetchone()[0] for status in ("new", "screened", "analyzed")}

    # ------------------------------------------------------------ files
    def download(self, bucket: str, path: str) -> bytes:
        file = self._file(bucket, path)
        if not file.is_file():
            raise StoreError(f"missing object {bucket}/{path}")
        return file.read_bytes()

    def upload(self, bucket: str, path: str, data: bytes, content_type: str, cache_seconds: int = 3600) -> None:
        file = self._file(bucket, path)
        file.parent.mkdir(parents=True, exist_ok=True)
        file.write_bytes(data)

    def object_path(self, bucket: str, path: str) -> Path:
        return self._file(bucket, path)

    # ------------------------------------------------------------ stickers
    @staticmethod
    def _check_sticker(row: Row) -> None:
        """The stickers table checks of the SQL migrations."""
        score = row.get("quality_score")
        if ("status" in row and row["status"] not in vocab.STICKER_STATUSES) or \
                (score is not None and not 0 <= score <= 100):
            raise StoreError("sticker violates table checks")

    def insert_sticker(self, row: Row) -> Row:
        stamp = self._stamp()
        full: Row = {"id": str(uuid.uuid4()), "rating": "general", "category": "reactions", "tags": [],
                     "emojis": [], "ai_generated": True, "status": "draft", "quality_reasons": [],
                     "created_at": stamp, "updated_at": stamp, **row}
        if len(full["emojis"]) > 3 or not 0 < full["bytes"] <= 500_000:
            raise StoreError("sticker violates table checks")
        self._check_sticker(full)
        full["search_text"] = " ".join(str(v) for v in (full.get("caption"), full.get("description"),
                                                         " ".join(full["tags"]), " ".join(full["emojis"]),
                                                         full["category"]) if v)
        if full["status"] == "published" and not full.get("published_at"):
            full["published_at"] = stamp
        self._insert("stickers", full)
        return self.sticker(full["id"])

    def sticker(self, sticker_id: str) -> Row:
        rows = self._select("stickers", "select * from stickers where id = ?", (sticker_id,))
        if not rows:
            raise StoreError("no such sticker")
        return rows[0]

    def update_sticker(self, sticker_id: str, fields: Row) -> None:
        """Admin emulation, e.g. approving a held sticker: update_sticker(id, {"status": "published"})."""
        self._check_sticker(fields)
        stamp = self._stamp()
        if fields.get("status") == "published" and not self.sticker(sticker_id).get("published_at"):
            fields = {**fields, "published_at": stamp}
        self._update("stickers", "id", sticker_id, {**fields, "updated_at": stamp})
        for pack_id in [r[0] for r in self.db.execute("select pack_id from pack_items where sticker_id = ?",
                                                      (sticker_id,))]:
            self._refresh_pack(pack_id, 0)

    def count_stickers_since(self, since: datetime) -> int:
        return self.db.execute("select count(*) from stickers where created_at >= ?", (iso(since),)).fetchone()[0]

    def published_stickers(self, limit: int = 5000) -> list[Row]:
        return self._select("stickers", "select * from stickers where status = 'published' "
                                        "order by published_at desc, id desc limit ?", (limit,))

    def render_fingerprints(self, limit: int) -> list[tuple[str, str, str | None]]:
        rows = self.db.execute("select id, render_phash, source_id from stickers where render_phash is not null "
                               "and status in ('published', 'draft') order by created_at desc limit ?",
                               (limit,)).fetchall()
        return [(r[0], r[1], r[2]) for r in rows]

    def stickers_by_id(self, ids: Sequence[str]) -> list[Row]:
        values = list(ids)
        rows: list[Row] = []
        for i in range(0, len(values), 200):
            batch = values[i:i + 200]
            marks = ",".join("?" for _ in batch)
            rows += self._select("stickers", f"select * from stickers where id in ({marks})", batch)
        return rows

    def rows(self, table: str, where: str = "1 = 1", params: Sequence[Any] = ()) -> list[Row]:
        """Test/report helper: raw decoded rows."""
        return self._select(table, f"select * from {table} where {where}", params)

    # ------------------------------------------------------------ packs
    def packs(self) -> list[Row]:
        return self._select("packs", "select * from packs order by created_at asc, volume asc")

    def pack_items(self, pack_ids: Sequence[str]) -> list[Row]:
        ids = list(pack_ids)
        if not ids:
            return []
        marks = ",".join("?" for _ in ids)
        return self._select("pack_items", f"select pack_id, sticker_id, position from pack_items where pack_id in "
                                          f"({marks}) order by position asc", ids)

    def insert_pack(self, row: Row) -> Row:
        stamp = self._stamp()
        full = {"id": str(uuid.uuid4()), "version": 1, "sticker_count": 0, "status": "draft",
                "pack_type": "series", "volume": 1, "rating": "general", "created_at": stamp,
                "updated_at": stamp, **row}
        self._insert("packs", full)
        return self._select("packs", "select * from packs where id = ?", (full["id"],))[0]

    def update_pack(self, pack_id: str, fields: Row) -> None:
        self._update("packs", "id", pack_id, {**fields, "updated_at": self._stamp()})

    def _refresh_pack(self, pack_id: str, changed_rows: int) -> None:
        with self.db:
            self.db.execute(
                "update packs set sticker_count = (select count(*) from pack_items i join stickers s on "
                "s.id = i.sticker_id where i.pack_id = ? and s.status = 'published'), version = version + ? "
                "where id = ?", (pack_id, changed_rows, pack_id))

    def add_pack_items(self, rows: Sequence[Row]) -> None:
        touched: dict[str, int] = {}
        stamp = self._stamp()
        for row in rows:
            if self._insert("pack_items", {"added_at": stamp, **row}, ignore=True):
                touched[row["pack_id"]] = touched.get(row["pack_id"], 0) + 1
        for pack_id, count in touched.items():
            self._refresh_pack(pack_id, count)

    def remove_pack_items(self, pack_id: str, sticker_ids: Sequence[str]) -> None:
        ids = list(sticker_ids)
        if not ids:
            return
        marks = ",".join("?" for _ in ids)
        with self.db:
            cursor = self.db.execute(f"delete from pack_items where pack_id = ? and sticker_id in ({marks})",
                                     (pack_id, *ids))
        self._refresh_pack(pack_id, cursor.rowcount)

    # ------------------------------------------------------------ trends
    def handnavigate_images(self, limit: int) -> list[Row]:
        return self._select("collected_images", "select * from collected_images order by collected_at desc limit ?",
                            (limit,))

    def add_handnavigate_image(self, row: Row, data: bytes) -> None:
        """Test/dev helper: what HandNavigate's import does on the VM (row + object in its bucket)."""
        self.upload("handnavigate-images", row["storage_path"], data, row["mime_type"])
        with self.db:
            self._insert("collected_images", {"id": str(uuid.uuid4()), "collected_at": self._stamp(),
                                              "rights_status": "reference_only", **row})

    def upsert_trends(self, rows: Sequence[Row]) -> int:
        for row in rows:
            data = self._encode("trends", {"id": str(uuid.uuid4()), "day": ist_day(self._now()).isoformat(),
                                           "created_at": self._stamp(), "active": True, **row})
            columns = ", ".join(data)
            updates = ", ".join(f"{c} = excluded.{c}" for c in data if c not in ("id", "day", "title", "created_at"))
            with self.db:
                self.db.execute(f"insert into trends ({columns}) values ({', '.join('?' for _ in data)}) "
                                f"on conflict(day, title) do update set {updates}", tuple(data.values()))
        return len(rows)

    def active_trends(self, since: datetime) -> list[Row]:
        return self._select("trends", "select * from trends where active = 1 and (created_at >= ? or source = "
                                      "'owner') order by score desc limit 60", (iso(since),))

    def deactivate_trends(self, before: datetime, sources: Sequence[str]) -> int:
        marks = ",".join("?" for _ in sources) or "''"
        with self.db:
            cursor = self.db.execute(f"update trends set active = 0 where active = 1 and created_at < ? and "
                                     f"source in ({marks})", (iso(before), *sources))
        return cursor.rowcount

    # ------------------------------------------------------------ ledger
    def log_ai_call(self, row: Row) -> None:
        self._insert("ai_calls", {"at": self._stamp(), **row})

    def ai_usage_since(self, since: datetime) -> dict[str, int]:
        stamp = iso(since)
        analyzed = self.db.execute("select count(*) from source_images where analyzed_at >= ?", (stamp,)).fetchone()[0]
        # Billed vision calls not counted as analysed sources: unusable analyses, and quality judgements
        # (one image each, usable or not).
        failed = self.db.execute("select count(*) from ai_calls where status = 200 and at >= ? and "
                                 "((purpose = ? and ok = 0) or purpose = ?)",
                                 (stamp, vocab.VISION_PURPOSE, vocab.QUALITY_PURPOSE)).fetchone()[0]
        marks = ",".join("?" for _ in vocab.TEXT_PURPOSES)
        text = self.db.execute(f"select count(*) from ai_calls where purpose in ({marks}) and status = 200 and at >= ?",
                               (*vocab.TEXT_PURPOSES, stamp)).fetchone()[0]
        return {"vision": analyzed + failed, "text": text}

    def start_run(self, stage: str) -> int | None:
        with self.db:
            cursor = self.db.execute("insert into worker_runs (started_at, stage) values (?, ?)",
                                     (self._stamp(), stage))
        return cursor.lastrowid

    def finish_run(self, run_id: int | None, ok: bool, summary: Row) -> None:
        if run_id is not None:
            self._update("worker_runs", "id", run_id, {"finished_at": self._stamp(), "ok": ok, "summary": summary})

    def last_run_at(self, stage: str) -> datetime | None:
        row = self.db.execute("select started_at from worker_runs where stage = ? and ok = 1 order by started_at desc "
                              "limit 1", (stage,)).fetchone()
        return parse_iso(row[0]) if row else None

    # ------------------------------------------------------------ admin emulation (tests, local QA)
    def add_takedown(self, *, reason: str, sha256: str | None = None, phash: str | None = None,
                     source_url: str | None = None) -> None:
        self._insert("takedowns", {"id": str(uuid.uuid4()), "sha256": sha256, "phash": phash,
                                   "source_url": source_url, "reason": reason, "created_at": self._stamp()})

    def take_down(self, sticker_id: str, reason: str) -> None:
        """Mirror of the take_down RPC for stickers."""
        sticker = self.sticker(sticker_id)
        self.update_sticker(sticker_id, {"status": "taken_down", "review_note": reason})
        self.add_takedown(reason=reason, sha256=sticker["sha256"], phash=sticker.get("phash"))
        if sticker.get("source_id"):
            source = self.source(sticker["source_id"])
            self.update_source(source["id"], {"status": "taken_down"})
            self.add_takedown(reason=reason, sha256=source["sha256"], source_url=source.get("source_url"))
        for pack_id in [r[0] for r in self.db.execute("select pack_id from pack_items where sticker_id = ?",
                                                      (sticker_id,))]:
            with self.db:
                self.db.execute("update packs set version = version + 1 where id = ?", (pack_id,))
