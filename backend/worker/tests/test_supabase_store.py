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
