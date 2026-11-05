"""Schema contract: SQL migrations vs the worker's vocabulary and defaults, and LocalStore upgrading old databases."""
from __future__ import annotations

import json
import re
import sqlite3
from pathlib import Path

import pytest

from stickerworker import vocab
from stickerworker.settings import DEFAULT_ROWS
from stickerworker.store import LocalStore, StoreError

MIGRATIONS = Path(__file__).resolve().parents[2] / "supabase" / "migrations"
GATE_MIGRATION = MIGRATIONS / "20261105000003_quality_gate.sql"


def all_sql() -> str:
    return "\n".join(path.read_text(encoding="utf-8") for path in sorted(MIGRATIONS.glob("*.sql")))


def statuses(values: str) -> set[str]:
    return {v.strip().strip("'") for v in values.split(",")}


def test_sticker_status_check_matches_the_worker_and_only_grows():
    sql = all_sql()
    table = sql[sql.index("create table public.stickers"):]
    table = table[:table.index(");")]
    original = statuses(re.search(r"status text not null default 'draft' check \(status in \(([^)]*)\)\)",
                                  table).group(1))
    widened = re.findall(r"add constraint stickers_status_check\s+check \(status in \(([^)]*)\)\)", sql)
    assert widened, "a migration must widen stickers_status_check"
    assert statuses(widened[-1]) == set(vocab.STICKER_STATUSES) and original < statuses(widened[-1])


@pytest.mark.parametrize("name", ["discover_stickers", "discover_packs", "discover_pack_stickers",
                                  "search_stickers"])
def test_public_views_and_rpcs_only_expose_published_rows(name):
    sql = all_sql()
    starts = [m.start() for m in re.finditer(rf"create (?:or replace )?(?:view|function) public\.{name}\b", sql)]
    assert starts
    body = sql[starts[-1]:]  # the definition that is live after all migrations
    end = re.search(r"\n(?:create|grant|insert|alter) ", body[1:])
    body = body[:end.start() + 1] if end else body
    assert "status = 'published'" in body


def test_anonymous_reads_of_stickers_are_published_only():
    policies = re.findall(r'create policy "[^"]+" on public\.stickers for select to anon[^;]*;', all_sql())
    assert policies and all("using (status = 'published')" in p for p in policies)


def test_quality_gate_migration_is_additive_and_seeds_the_worker_defaults():
    names = sorted(p.name for p in MIGRATIONS.glob("*.sql"))
    assert names.index(GATE_MIGRATION.name) > names.index("20261105000002_pack_versions.sql")
    sql = GATE_MIGRATION.read_text(encoding="utf-8")
    code = "\n".join(line.split("--")[0] for line in sql.lower().splitlines())
    for destructive in ("drop table", "drop column", "drop view", "drop function", "truncate", "delete from",
                        "alter column", "update public."):
        assert destructive not in code
    seed = re.search(r"\('quality_gate',\s*'(\{[^']*\})'\)", sql)
    assert seed and json.loads(seed.group(1)) == DEFAULT_ROWS["quality_gate"]
    assert "on conflict (key) do nothing" in code  # never overwrites an owner's setting


OLD_SCHEMA = """
create table settings (key text primary key, value text not null);
create table stickers (
  id text primary key, source_id text, recipe text not null, kind text not null, rating text not null,
  category text not null, caption text, language text, tags text not null default '[]',
  emojis text not null default '[]', description text, file_path text not null unique,
  sha256 text not null unique, phash text, bytes integer not null, ai_generated integer not null default 1,
  credit text, quality integer, trend_id text, status text not null default 'draft', review_note text,
  search_text text, published_at text, created_at text not null, updated_at text not null);
insert into stickers (id, recipe, kind, rating, category, file_path, sha256, bytes, status, created_at, updated_at)
  values ('old', 'caption', 'static', 'general', 'office', 'old.webp', 'aa', 100, 'published', 'x', 'x');
insert into settings (key, value) values ('publish_policy', '{"auto_publish": false}');
"""


def test_local_store_upgrades_an_older_database_without_losing_data(tmp_path):
    root = tmp_path / "out"
    root.mkdir()
    with sqlite3.connect(root / "local.sqlite3") as db:
        db.executescript(OLD_SCHEMA)
    store = LocalStore(root)
    columns = {row[1] for row in store.db.execute("pragma table_info(stickers)")}
    assert {"quality_score", "quality_reasons", "quality_signals", "render_phash"} <= columns
    old = store.sticker("old")
    assert old["status"] == "published" and old["quality_reasons"] == [] and old["quality_score"] is None
    settings = store.load_settings()
    assert settings["publish_policy"] == {"auto_publish": False}  # owner edit kept
    assert settings["quality_gate"] == DEFAULT_ROWS["quality_gate"]  # missing row added
    held = store.insert_sticker({"recipe": "caption", "kind": "static", "file_path": "h.webp", "sha256": "bb",
                                 "bytes": 100, "status": "held", "quality_score": 41, "quality_reasons": ["blurry"],
                                 "quality_signals": {"sharpness": 3.1}, "render_phash": "0123456789abcdef"})
    assert held["quality_reasons"] == ["blurry"] and held["quality_signals"] == {"sharpness": 3.1}
    assert store.published_stickers() == [store.sticker("old")]
    assert store.render_fingerprints(10) == []  # held stickers are not duplicate targets; "old" has no hash
    for bad in ({"status": "approved"}, {"quality_score": 101}):
        with pytest.raises(StoreError):
            store.insert_sticker({"recipe": "caption", "kind": "static", "file_path": "x.webp", "sha256": "cc",
                                  "bytes": 100, **bad})
