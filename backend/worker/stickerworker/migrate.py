"""Applies backend/supabase/migrations/*.sql to the configured Postgres, each file once, in name order.

Uses the same connection settings as HandNavigate's windows-agent/.env (SUPABASE_DB_HOST/PORT/USER/PASSWORD/NAME),
so StickerDock and HandNavigate can share one project. Applied files are recorded in public.stickerdock_migrations
with their checksum; a changed, already-applied file stops the run instead of being re-run.
"""
from __future__ import annotations

import hashlib
import os
from pathlib import Path
from typing import Any

MIGRATIONS = Path(__file__).resolve().parents[2] / "supabase" / "migrations"

LEDGER = """create table if not exists public.stickerdock_migrations (
  name text primary key, sha256 text not null, applied_at timestamptz not null default now());
alter table public.stickerdock_migrations enable row level security;"""


def connection_settings(env: dict[str, str] | None = None) -> dict[str, Any]:
    env = dict(os.environ if env is None else env)
    missing = [k for k in ("SUPABASE_DB_HOST", "SUPABASE_DB_USER", "SUPABASE_DB_PASSWORD") if not env.get(k)]
    if missing:
        raise RuntimeError("Set " + ", ".join(missing) + " in .env (Supabase → Project Settings → Database).")
    return {"host": env["SUPABASE_DB_HOST"], "port": int(env.get("SUPABASE_DB_PORT") or 5432),
            "user": env["SUPABASE_DB_USER"], "password": env["SUPABASE_DB_PASSWORD"],
            "dbname": env.get("SUPABASE_DB_NAME") or "postgres", "sslmode": "require", "connect_timeout": 15}


def pending(applied: dict[str, str], folder: Path = MIGRATIONS) -> list[tuple[str, str, str]]:
    """(name, sha256, sql) of files not applied yet; raises if an applied file was edited afterwards."""
    todo = []
    for file in sorted(folder.glob("*.sql")):
        sql = file.read_text(encoding="utf-8")
        digest = hashlib.sha256(sql.encode("utf-8")).hexdigest()
        if file.name in applied:
            if applied[file.name] != digest:
                raise RuntimeError(f"{file.name} changed after it was applied; add a new migration instead.")
            continue
        todo.append((file.name, digest, sql))
    return todo


def apply(dry_run: bool = False, env: dict[str, str] | None = None) -> list[str]:
    import psycopg  # optional dependency: pip install -e ".[db]"

    with psycopg.connect(**connection_settings(env), autocommit=False) as conn:
        with conn.cursor() as cur:
            cur.execute(LEDGER)
            cur.execute("select name, sha256 from public.stickerdock_migrations")
            applied = dict(cur.fetchall())
        conn.commit()
        done = []
        for name, digest, sql in pending(applied):
            if dry_run:
                done.append(name)
                continue
            with conn.cursor() as cur:  # one transaction per file: a failure leaves earlier files applied
                cur.execute(sql)
                cur.execute("insert into public.stickerdock_migrations(name, sha256) values (%s, %s)", (name, digest))
            conn.commit()
            done.append(name)
        return done
