"""SupabaseStore request shapes over httpx.MockTransport, and DryRunStore never writing."""
from __future__ import annotations

import json
from datetime import UTC, datetime

import httpx
import pytest

from stickerworker.store import DryRunStore, StoreError, SupabaseStore

URL = "https://project.supabase.co"
KEY = "service-role-test-key"


def make(handler):
    seen: list[httpx.Request] = []

    def record(request: httpx.Request) -> httpx.Response:
        seen.append(request)
        return handler(request)

    return SupabaseStore(URL, KEY, transport=httpx.MockTransport(record), sleep=lambda s: None), seen


def test_every_request_carries_service_role_headers():
    store, seen = make(lambda r: httpx.Response(200, json=[{"key": "budgets", "value": {"vision_per_day": 5}}]))
    assert store.load_settings() == {"budgets": {"vision_per_day": 5}}
    assert seen[0].headers["apikey"] == KEY and seen[0].headers["authorization"] == f"Bearer {KEY}"
    assert seen[0].url.path == "/rest/v1/settings"


def test_claim_uses_rpc_by_default():
    store, seen = make(lambda r: httpx.Response(200, json=[{"id": "a", "attempts": 1}]))
    assert store.claim_sources("new", "new", 25, 600) == [{"id": "a", "attempts": 1}]
    assert seen[0].method == "POST" and seen[0].url.path == "/rest/v1/rpc/claim_sources"
    assert json.loads(seen[0].content) == {"p_status": "new", "p_next": "new", "p_limit": 25, "p_lease_seconds": 600}


def test_claim_by_score_orders_and_claims_optimistically():
    def handler(request: httpx.Request) -> httpx.Response:
        if request.method == "GET":
            return httpx.Response(200, json=[{"id": "x", "attempts": 0}, {"id": "y", "attempts": 2}])
        if request.url.params["id"] == "eq.x":
            return httpx.Response(200, json=[{"id": "x", "attempts": 1, "status": "analyzing"}])
        return httpx.Response(200, json=[])  # someone else claimed y first
    store, seen = make(handler)
    claimed = store.claim_sources("screened", "analyzing", 2, 900, by_score=True)
    assert [row["id"] for row in claimed] == ["x"]
    query = seen[0].url.params
    assert query["order"] == "prescreen_score.desc.nullslast,collected_at.asc"
    assert query["status"] == "eq.screened" and query["attempts"] == "lt.5"
    patch = seen[1]
    assert patch.method == "PATCH" and patch.url.params["attempts"] == "eq.0"
    assert json.loads(patch.content)["attempts"] == 1 and patch.headers["prefer"] == "return=representation"


def test_reclaim_stale_resets_expired_rows_and_decrements_attempts():
    def handler(request: httpx.Request) -> httpx.Response:
        if request.method == "GET":  # find rows stuck past their lease
            assert request.url.params["status"] == "eq.analyzing"
            assert request.url.params["locked_until"].startswith("lt.")
            return httpx.Response(200, json=[{"id": "a", "attempts": 2}, {"id": "b", "attempts": 1}])
        return httpx.Response(200, json=[])  # PATCH back to screened
    store, seen = make(handler)
    assert store.reclaim_stale("analyzing", "screened") == 2
    patches = [r for r in seen if r.method == "PATCH"]
    assert len(patches) == 2
    first = json.loads(patches[0].content)
    assert first == {"status": "screened", "locked_until": None, "attempts": 1}  # 2 - 1
    assert patches[0].url.params["id"] == "eq.a"


def test_storage_upload_and_download_paths():
    store, seen = make(lambda r: httpx.Response(200, content=b"RIFF....WEBP") if r.method == "GET"
                       else httpx.Response(200, json={"Key": "stickers/a b.webp"}))
    store.upload("stickers", "a b.webp", b"data", "image/webp", cache_seconds=60)
    assert seen[0].url.raw_path == b"/storage/v1/object/stickers/a%20b.webp"
    assert seen[0].headers["x-upsert"] == "true" and seen[0].headers["content-type"] == "image/webp"
    assert seen[0].headers["cache-control"] == "max-age=60"
    assert store.download("sources", "upload/ab/x.jpg") == b"RIFF....WEBP"
    assert seen[1].url.path == "/storage/v1/object/sources/upload/ab/x.jpg"


def test_counts_parse_content_range_for_budgets():
    store, seen = make(lambda r: httpx.Response(200, json=[], headers={"content-range": "0-0/7"}))
    usage = store.ai_usage_since(datetime(2026, 11, 4, 18, 30, tzinfo=UTC))
    assert usage == {"vision": 14, "text": 7}  # analysed today + failed-but-billed vision calls
    assert all(r.headers["prefer"] == "count=exact" for r in seen)
    text_query = seen[2].url.params
    assert text_query["purpose"] == 'in.("caption","judge","pack_title","trend")' and text_query["status"] == "eq.200"


def test_insert_source_ignores_duplicates():
    store, seen = make(lambda r: httpx.Response(201, content=b"[]"))
    assert store.insert_source({"sha256": "0" * 64}) is None
    assert seen[0].url.params["on_conflict"] == "sha256"
    assert "resolution=ignore-duplicates" in seen[0].headers["prefer"]


def test_errors_are_raised_without_secrets_and_5xx_retried():
    store, seen = make(lambda r: httpx.Response(503) if len(seen) <= 2 else
                       httpx.Response(401, json={"message": "JWT expired", "code": "PGRST301"}))
    with pytest.raises(StoreError) as info:
        store.protected_people()
    assert "401" in str(info.value) and KEY not in str(info.value)
    assert len(seen) == 3


def test_insecure_url_refused():
    with pytest.raises(StoreError):
        SupabaseStore("http://project.supabase.co", KEY)


def test_judge_calls_are_billed_vision_and_keep_three_count_requests():
    store, seen = make(lambda r: httpx.Response(200, json=[], headers={"content-range": "0-0/3"}))
    assert store.ai_usage_since(datetime(2026, 11, 4, 18, 30, tzinfo=UTC)) == {"vision": 6, "text": 3}
    billed = seen[1].url.params
    assert billed["or"] == "(and(purpose.eq.analyze,ok.is.false),purpose.eq.quality_judge)"
    assert billed["status"] == "eq.200" and len(seen) == 3


def test_render_fingerprints_page_through_published_and_draft_stickers():
    def handler(request: httpx.Request) -> httpx.Response:
        offset = int(request.url.params["offset"])
        rows = [{"id": f"s{offset + i}", "render_phash": "0123456789abcdef", "source_id": None}
                for i in range(1000 if offset == 0 else 5)]
        return httpx.Response(200, json=rows)
    store, seen = make(handler)
    rows = store.render_fingerprints(5000)
    assert len(rows) == 1005 and rows[0] == ("s0", "0123456789abcdef", None)
    query = seen[0].url.params
    assert query["select"] == "id,render_phash,source_id" and query["render_phash"] == "not.is.null"
    assert query["status"] == "in.(published,draft)" and query["order"] == "created_at.desc"
    assert [r.url.params["offset"] for r in seen] == ["0", "1000"]


def test_prescreen_fingerprints_page_past_postgrest_max_rows():
    # PostgREST answers at most 1000 rows per request; duplicate checks must still see older images.
    def handler(request: httpx.Request) -> httpx.Response:
        offset = int(request.url.params["offset"])
        table = request.url.path.rsplit("/", 1)[-1]
        count = 1000 if offset == 0 else 7
        rows = [{"id": f"{table[0]}{offset + i}", "phash": "f" * 16} for i in range(count)]
        return httpx.Response(200, json=rows)
    store, seen = make(handler)
    rows = store.fingerprints(5000)
    assert sum(1 for key, _ in rows if key.startswith("source:")) == 1007
    assert sum(1 for key, _ in rows if key.startswith("sticker:")) == 1007
    assert [(r.url.path.rsplit("/", 1)[-1], r.url.params["offset"]) for r in seen] == [
        ("source_images", "0"), ("source_images", "1000"), ("stickers", "0"), ("stickers", "1000")]
    assert seen[0].url.params["status"] == "not.in.(rejected,failed)" and seen[2].url.params["status"] == "neq.rejected"


def test_held_sticker_insert_carries_the_gate_fields():
    store, seen = make(lambda r: httpx.Response(201, json=[{"id": "x", "status": "held"}]))
    store.insert_sticker({"id": "x", "status": "held", "quality_score": 41, "quality_reasons": ["blurry"],
                          "quality_signals": {"sharpness": 3.1}, "render_phash": "0123456789abcdef"})
    body = json.loads(seen[0].content)[0]
    assert body["status"] == "held" and body["quality_score"] == 41 and body["quality_reasons"] == ["blurry"]
    assert body["quality_signals"] == {"sharpness": 3.1} and body["render_phash"] == "0123456789abcdef"


def test_dry_run_counts_planned_stickers_per_status(tmp_path):
    store, _ = make(lambda r: httpx.Response(200, json=[]))
    dry = DryRunStore(store, tmp_path / "preview")
    for status in ("published", "held", "held"):
        dry.insert_sticker({"file_path": f"{status}.webp", "status": status})
    assert dry.summary()["insert_sticker"] == 3
    assert dry.summary()["stickers_held"] == 2 and dry.summary()["stickers_published"] == 1


def test_dry_run_store_sends_only_reads(tmp_path):
    store, seen = make(lambda r: httpx.Response(200, json=[]))
    dry = DryRunStore(store, tmp_path / "preview")
    dry.claim_sources("analyzed", "analyzed", 5, 900)
    dry.update_source("x", {"status": "used"})
    dry.upload("stickers", "s.webp", b"bytes", "image/webp")
    dry.insert_sticker({"file_path": "s.webp"})
    dry.insert_pack({"slug": "office-static-general-v1"})
    dry.add_pack_items([{"pack_id": "p", "sticker_id": "s", "position": 0}])
    dry.log_ai_call({"purpose": "analyze"})
    assert {r.method for r in seen} == {"GET"}
    assert (tmp_path / "preview" / "stickers" / "s.webp").read_bytes() == b"bytes"
    assert dry.summary()["update_source"] == 1 and dry.summary()["upload"] == 1


def test_new_secret_keys_go_only_in_the_apikey_header():
    seen = []
    store = SupabaseStore(URL, "sb_secret_abc123", transport=httpx.MockTransport(
        lambda r: (seen.append(r), httpx.Response(200, json=[]))[1]), sleep=lambda s: None)
    store.load_settings()
    assert seen[0].headers["apikey"] == "sb_secret_abc123"
    assert "authorization" not in seen[0].headers


def test_dotenv_fills_only_unset_values(tmp_path):
    from stickerworker.cli import load_dotenv
    file = tmp_path / ".env"
    lines = ["# comment", "SUPABASE_URL=https://x.supabase.co", "SUPABASE_SECRET_KEY=sb_secret_1   # note",
             "GROQ_API_KEYS=", "EXISTING=from-file"]
    file.write_text("\n".join(lines) + "\n", encoding="utf-8")
    env = {"EXISTING": "from-env"}
    assert load_dotenv(file, env) == 2
    assert env == {"EXISTING": "from-env", "SUPABASE_URL": "https://x.supabase.co", "SUPABASE_SECRET_KEY": "sb_secret_1"}


def test_migrations_apply_in_order_once_and_refuse_edited_files(tmp_path):
    from stickerworker.migrate import connection_settings, pending
    (tmp_path / "002_b.sql").write_text("select 2;", encoding="utf-8")
    (tmp_path / "001_a.sql").write_text("select 1;", encoding="utf-8")
    todo = pending({}, tmp_path)
    assert [name for name, _, _ in todo] == ["001_a.sql", "002_b.sql"]
    applied = {todo[0][0]: todo[0][1]}
    assert [name for name, _, _ in pending(applied, tmp_path)] == ["002_b.sql"]
    with pytest.raises(RuntimeError, match="changed after it was applied"):
        pending({"001_a.sql": "0" * 64}, tmp_path)
    with pytest.raises(RuntimeError, match="SUPABASE_DB_HOST"):
        connection_settings({})
    assert connection_settings({"SUPABASE_DB_HOST": "h", "SUPABASE_DB_USER": "u", "SUPABASE_DB_PASSWORD": "p"})["dbname"] == "postgres"


def test_the_real_migration_folder_is_in_order():
    from stickerworker.migrate import MIGRATIONS, pending
    names = [name for name, _, _ in pending({}, MIGRATIONS)]
    assert names[:3] == ["20261105000001_core.sql", "20261105000002_pack_versions.sql", "20261105000003_quality_gate.sql"]
