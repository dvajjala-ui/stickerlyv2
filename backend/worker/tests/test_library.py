"""HandNavigate's library (collected_images in the same project) feeding the sticker queue."""
from __future__ import annotations

import hashlib
import io

from PIL import Image

from stickerworker.pipeline import library
from stickerworker.store.base import SOURCES_BUCKET, StoreError


def png(color: tuple[int, int, int], size: tuple[int, int] = (400, 300)) -> bytes:
    buffer = io.BytesIO()
    Image.new("RGB", size, color).save(buffer, "PNG")
    return buffer.getvalue()


def add(store, data: bytes, topic: str = "memes", source: str = "pinterest") -> str:
    sha = hashlib.sha256(data).hexdigest()
    store.add_handnavigate_image({
        "source": source, "topic_key": topic, "source_page_url": "https://in.pinterest.com/search/pins/?q=memes",
        "source_image_url": f"https://i.pinimg.com/736x/{sha[:8]}.png", "storage_path": f"{source}/{topic}/{sha}.png",
        "sha256": sha, "mime_type": "image/png"}, data)
    return sha


def test_without_a_library_the_stage_does_nothing(ctx):
    assert library.run(ctx) == {"seen": 0}


def test_new_images_are_queued_once_with_their_provenance(ctx, store):
    sha = add(store, png((200, 30, 30)), topic="hinglish_memes")
    first = library.run(ctx)
    assert first["queued"] == 1
    row = store._select("source_images", "select * from source_images", ())[0]
    assert row["sha256"] == sha and row["collector"] == "handnavigate" and row["site"] == "pinterest"
    assert row["topic_hint"] == "hinglish memes" and row["license"] == "reference_only" and row["status"] == "new"
    assert row["width"] == 400 and row["page_url"].startswith("https://in.pinterest.com/")
    assert store.download(SOURCES_BUCKET, row["storage_path"]) == store.download("handnavigate-images", f"pinterest/hinglish_memes/{sha}.png")
    assert library.run(ctx)["already_queued"] == 1, "a second run queues nothing new"


def test_changed_or_missing_bytes_are_not_queued(ctx, store):
    sha = add(store, png((10, 200, 10)))
    store.upload("handnavigate-images", f"pinterest/memes/{sha}.png", png((1, 2, 3)), "image/png")
    assert library.run(ctx)["skipped_changed"] == 1
    assert store.existing_sha256([sha]) == set()


def test_dry_run_plans_without_writing(ctx, store):
    add(store, png((0, 0, 200)))
    ctx.dry_run = True
    assert library.run(ctx)["planned"] == 1
    assert store._select("source_images", "select * from source_images", ()) == []


def test_a_project_without_handnavigate_tables_is_skipped(ctx, store, monkeypatch):
    def missing(limit):
        raise StoreError("GET /rest/v1/collected_images -> HTTP 404 relation does not exist")
    monkeypatch.setattr(store, "handnavigate_images", missing)
    assert library.run(ctx)["skipped"] == "no_handnavigate_library"
