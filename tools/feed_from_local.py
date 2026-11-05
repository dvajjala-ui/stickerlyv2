"""Turn the AI worker's offline output (LocalStore) into a feed folder for tools/dev_discover.py.

  python tools/feed_from_local.py backend/worker/out  work/dev-feed
Only published stickers and packs are exported — exactly what phones would see from Supabase.
"""
from __future__ import annotations

import json
import pathlib
import shutil
import sqlite3
import sys


def main(src: str, dest: str) -> None:
    out = pathlib.Path(src)
    feed_dir = pathlib.Path(dest)
    (feed_dir / "stickers").mkdir(parents=True, exist_ok=True)
    db = sqlite3.connect(out / "local.sqlite3")
    db.row_factory = sqlite3.Row
    stickers = []
    for row in db.execute("select * from stickers where status = 'published' order by coalesce(published_at, created_at) desc"):
        source = out / "storage" / "stickers" / row["file_path"]
        if not source.is_file():
            continue
        shutil.copyfile(source, feed_dir / "stickers" / row["file_path"])
        stickers.append({
            "id": row["id"], "kind": row["kind"], "rating": row["rating"], "category": row["category"],
            "caption": row["caption"], "tags": json.loads(row["tags"] or "[]"), "emojis": json.loads(row["emojis"] or "[]"),
            "file_path": row["file_path"], "sha256": row["sha256"],
            "credit": json.loads(row["credit"]) if row["credit"] else None,
            "ai_generated": bool(row["ai_generated"]), "published_at": row["published_at"] or row["created_at"],
        })
    published = {s["id"] for s in stickers}
    by_id = {s["id"]: s for s in stickers}
    packs = []
    for p in db.execute("select * from packs where status = 'published'"):
        ids = [r[0] for r in db.execute("select sticker_id from pack_items where pack_id = ? order by position", (p["id"],))
               if r[0] in published]
        if len(ids) < 3:
            continue
        cover = by_id.get(p["cover_sticker_id"]) or by_id[ids[0]]
        packs.append({"id": p["id"], "title": p["title"], "description": p["description"], "kind": p["kind"],
                      "rating": p["rating"], "category": p["category"], "version": p["version"], "sticker_ids": ids,
                      "cover_path": cover["file_path"], "updated_at": p["updated_at"] if "updated_at" in p.keys() else None})
    (feed_dir / "feed.json").write_text(json.dumps({"stickers": stickers, "packs": packs}, indent=1), encoding="utf-8")
    print(f"exported {len(stickers)} stickers and {len(packs)} packs to {feed_dir}")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
