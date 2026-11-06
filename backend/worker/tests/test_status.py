"""The `status` operator snapshot: published content, queue and today's AI spend."""
from __future__ import annotations

from stickerworker.cli import status_summary


def test_status_summary_counts_published_content_and_queue(store):
    # Empty store: a well-formed zeroed snapshot.
    empty = status_summary(store)
    assert empty["stickers_published"] == 0 and empty["stickers_held"] == 0
    assert empty["packs_total"] == 0 and empty["packs_published"] == 0
    assert set(empty["sources_pending"]) == {"new", "screened", "analyzed"}
    assert set(empty["ai_today"]) == {"vision", "text"}

    store.insert_sticker({"recipe": "caption", "kind": "static", "file_path": "a.webp", "sha256": "aa",
                          "bytes": 100, "status": "published"})
    store.insert_sticker({"recipe": "caption", "kind": "static", "file_path": "b.webp", "sha256": "bb",
                          "bytes": 100, "status": "held", "quality_score": 40})
    store.insert_pack({"title": "Live pack", "status": "published", "kind": "static", "category": "memes"})
    store.insert_pack({"title": "Draft pack", "status": "draft", "kind": "static", "category": "memes"})

    s = status_summary(store)
    assert s["stickers_published"] == 1          # the held one is not counted as published
    assert s["stickers_held"] == 1
    assert s["stickers"] == {"published": 1, "held": 1}
    assert s["packs_total"] == 2 and s["packs_published"] == 1
