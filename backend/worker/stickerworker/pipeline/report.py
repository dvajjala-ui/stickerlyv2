"""Local run report: report.json plus a static index.html gallery of packs and stickers."""
from __future__ import annotations

import html
import json
from pathlib import Path
from typing import Any

from ..store.local import LocalStore


def write_local_report(store: LocalStore, out: Path) -> dict[str, Any]:
    stickers = store.rows("stickers")
    packs = store.packs()
    items = store.pack_items([p["id"] for p in packs])
    by_pack: dict[str, list[str]] = {}
    for item in items:
        by_pack.setdefault(item["pack_id"], []).append(item["sticker_id"])
    by_id = {s["id"]: s for s in stickers}
    report = {
        "stickers": [{k: s.get(k) for k in ("id", "recipe", "kind", "rating", "category", "status", "caption",
                                            "quality", "bytes", "review_note", "file_path")} for s in stickers],
        "packs": [{"title": p["title"], "kind": p["kind"], "rating": p["rating"], "status": p["status"],
                   "pack_type": p["pack_type"], "volume": p["volume"], "stickers": by_pack.get(p["id"], [])}
                  for p in packs],
        "sources": [{k: r.get(k) for k in ("id", "status", "last_error", "topic_hint", "prescreen_score")}
                    for r in store.rows("source_images")],
    }
    (out / "report.json").write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding="utf-8")

    def tile(sticker: dict[str, Any]) -> str:
        label = html.escape(f"{sticker['recipe']} · {sticker['kind']} · {sticker['rating']} · {sticker['status']}")
        caption = html.escape(sticker.get("caption") or "")
        return (f'<figure><img src="storage/stickers/{html.escape(sticker["file_path"])}" loading="lazy" alt="">'
                f"<figcaption>{label}<br>{caption}</figcaption></figure>")

    sections = []
    for pack in packs:
        tiles = "".join(tile(by_id[s]) for s in by_pack.get(pack["id"], []) if s in by_id)
        sections.append(f"<h2>{html.escape(pack['title'])} <small>({pack['kind']}, {pack['rating']}, "
                        f"{pack['status']})</small></h2><div class=grid>{tiles}</div>")
    loose = [s for s in stickers if not any(s["id"] in v for v in by_pack.values())]
    sections.append("<h2>Not in a pack (draft or below threshold)</h2><div class=grid>"
                    + "".join(tile(s) for s in loose) + "</div>")
    page = ("<!doctype html><meta charset=utf-8><title>StickerDock local run</title><style>"
            "body{font:14px system-ui;margin:16px;background:#e9e4dc}h2{margin-top:28px}"
            ".grid{display:flex;flex-wrap:wrap;gap:12px}figure{margin:0;width:180px}"
            "img{width:180px;height:180px;background:repeating-conic-gradient(#ccc 0 25%,#fff 0 50%) 0 0/16px 16px}"
            "figcaption{font-size:11px;color:#333}</style>" + "".join(sections))
    (out / "index.html").write_text(page, encoding="utf-8")
    return {"report": str(out / "report.json"), "gallery": str(out / "index.html"), "stickers": len(stickers),
            "published": sum(1 for s in stickers if s["status"] == "published"), "packs": len(packs)}
