"""Whole pipeline offline: CLI `run --local` with LocalStore + FakeAI on generated sample images."""
from __future__ import annotations

import json
import shutil
from pathlib import Path

import pytest
from samples import encode, photo, write_sample_folder

from stickerworker.cli import main
from stickerworker.imaging.validate import inspect_sticker
from stickerworker.store import LocalStore


@pytest.fixture(scope="module")
def run_dir(tmp_path_factory: pytest.TempPathFactory) -> tuple[Path, Path, dict]:
    root = tmp_path_factory.mktemp("e2e")
    inbox, out = root / "in", root / "out"
    write_sample_folder(inbox)
    with pytest.MonkeyPatch.context() as patch:
        for name in ("GROQ_API_KEYS", "GROQ_API_KEY", "SUPABASE_URL", "SUPABASE_SERVICE_ROLE_KEY"):
            patch.delenv(name, raising=False)
        assert main(["run", "--stage", "all", "--local", str(inbox), "--out", str(out)]) == 0
    return inbox, out, json.loads((out / "report.json").read_text(encoding="utf-8"))


def sources_by_hint(store: LocalStore) -> dict[str, list[dict]]:
    found: dict[str, list[dict]] = {}
    for row in store.rows("source_images"):
        found.setdefault(row["topic_hint"], []).append(row)
    return found


def test_every_output_is_a_valid_whatsapp_sticker(run_dir):
    _, out, report = run_dir
    store = LocalStore(out)
    stickers = store.rows("stickers")
    assert len(stickers) >= 15
    for sticker in stickers:
        check = inspect_sticker(store.download("stickers", sticker["file_path"]))
        assert check.ok, (sticker["recipe"], check.problems)
        assert check.kind == sticker["kind"] and check.bytes == sticker["bytes"]
        assert len(sticker["emojis"]) <= 3
    assert {s["recipe"] for s in stickers} >= {"direct", "caption", "motion"}
    assert {s["kind"] for s in stickers} == {"static", "animated"}
    assert (out / "index.html").is_file() and report["packs"]


def test_sources_are_screened_and_policed(run_dir):
    _, out, _ = run_dir
    sources = sources_by_hint(LocalStore(out))
    assert sources["tiny"][0]["last_error"] == "too_small"
    assert sources["blank"][0]["last_error"] == "blank"
    assert sources["office reaction person copy"][0]["last_error"].startswith("duplicate_of:source:")
    assert sources["explicit nsfw test"][0]["last_error"] == "policy:explicit_image"
    assert sources["amitabh bachchan meme"][0]["last_error"] == "policy:protected_person:Amitabh Bachchan"
    assert {r["status"] for r in sources["office reaction person"]} == {"used"}
    assert sources["meme text funny"][0]["site"] == "example"  # sidecar metadata
    assert sources["meme text funny"][0]["source_url"] == "https://example.org/meme/1"


def test_ratings_follow_the_rules(run_dir):
    _, out, _ = run_dir
    store = LocalStore(out)
    by_source = {}
    for sticker in store.rows("stickers"):
        by_source.setdefault(store.source(sticker["source_id"])["topic_hint"], []).append(sticker)
    assert by_source["baby smile"] and {s["rating"] for s in by_source["baby smile"]} == {"general"}
    assert {s["rating"] for s in by_source["adult nonveg couple"]} == {"adult"}
    direct = [s for s in by_source["meme text funny"] if s["recipe"] == "direct"]
    assert direct and direct[0]["ai_generated"] is False and direct[0]["credit"]["url"].startswith("https://")
    assert all(s["ai_generated"] for s in by_source["office reaction person"])


def test_packs_respect_whatsapp_and_mixing_rules(run_dir):
    _, out, _ = run_dir
    store = LocalStore(out)
    rows = store.db.execute("select p.id, p.kind, p.rating, s.kind, s.rating, s.status from pack_items i "
                            "join packs p on p.id = i.pack_id join stickers s on s.id = i.sticker_id").fetchall()
    assert rows
    assert all(p_kind == s_kind and p_rating == s_rating and status == "published"
               for _, p_kind, p_rating, s_kind, s_rating, status in rows)
    for pack in store.packs():
        assert pack["sticker_count"] <= 30
        assert pack["status"] != "published" or pack["sticker_count"] >= 3
    assert any(p["status"] == "published" for p in store.packs())


def test_ledger_and_runs_are_recorded(run_dir):
    _, out, _ = run_dir
    store = LocalStore(out)
    calls = store.rows("ai_calls")
    assert calls and {c["provider"] for c in calls} == {"fake"}
    assert {c["purpose"] for c in calls} >= {"analyze", "caption", "judge", "pack_title"}
    runs = store.rows("worker_runs")
    assert {r["stage"] for r in runs} == {"trends", "prescreen", "analyze", "create", "compose"}
    assert all(r["ok"] and r["finished_at"] for r in runs)


def test_rerun_is_idempotent(run_dir):
    inbox, out, _ = run_dir
    store = LocalStore(out)
    before = (len(store.rows("stickers")), len(store.rows("source_images")), len(store.packs()))
    assert main(["run", "--local", str(inbox), "--out", str(out)]) == 0
    assert (len(store.rows("stickers")), len(store.rows("source_images")), len(store.packs())) == before


def test_takedown_blocks_reingest_of_a_reencoded_copy(tmp_path):
    inbox, out = tmp_path / "in", tmp_path / "out"
    inbox.mkdir()
    original = photo(seed=77, hue=20)
    (inbox / "office_reaction_person.jpg").write_bytes(encode(original, "JPEG", quality=92))
    assert main(["run", "--local", str(inbox), "--out", str(out)]) == 0
    store = LocalStore(out)
    published = [s for s in store.rows("stickers") if s["status"] == "published"]
    assert published
    store.take_down(published[0]["id"], "copyright complaint")
    again = tmp_path / "again"
    again.mkdir()
    (again / "office_reaction_person_reupload.png").write_bytes(encode(original.resize((560, 420)), "PNG"))
    assert main(["run", "--local", str(again), "--out", str(out)]) == 0
    reupload = [r for r in store.rows("source_images") if r["topic_hint"] == "office reaction person reupload"]
    assert reupload[0]["status"] == "taken_down" and reupload[0]["last_error"] == "takedown:phash"


def test_dry_run_writes_nothing(tmp_path):
    inbox, out = tmp_path / "in", tmp_path / "out"
    write_sample_folder(inbox)
    shutil.rmtree(out, ignore_errors=True)
    assert main(["run", "--local", str(inbox), "--out", str(out), "--dry-run", "--stage", "prescreen"]) == 0
    store = LocalStore(out)
    assert store.rows("source_images") == [] and store.rows("stickers") == []
