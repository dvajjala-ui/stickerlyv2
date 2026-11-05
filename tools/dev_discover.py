"""Local stand-in for the Supabase Discover API, for testing the phone app before the real project exists.

Serves the same endpoints DiscoverClient uses:
  GET  /rest/v1/discover_stickers   (order, limit, offset, rating=eq.general, category=eq.x)
  GET  /rest/v1/discover_packs      (limit, rating=eq.general, id=eq.x)
  POST /rest/v1/rpc/discover_pack_stickers   {"p_pack": id}
  POST /rest/v1/rpc/search_stickers          {"q", "include_adult", "max_rows"}
  POST /rest/v1/rpc/report_content           (logged, 204)
  GET  /storage/v1/object/public/stickers/<file>

Feed folder layout (written by `python -m stickerworker export-feed` or by hand):
  feed.json  {"stickers": [{id, kind, rating, category, caption, tags, emojis, file_path, ai_generated, credit,
                           published_at}], "packs": [{id, title, description, kind, rating, category, version,
                           sticker_ids: [...], cover_path, updated_at}]}
  stickers/<file_path>
Usage:  python tools/dev_discover.py FEED_DIR [--port 8787]   then   adb reverse tcp:8787 tcp:8787
"""
from __future__ import annotations

import argparse
import json
import pathlib
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

FEED: dict = {}
ROOT = pathlib.Path(".")


def _public(sticker: dict) -> dict:
    return {k: sticker.get(k) for k in ("id", "kind", "rating", "category", "caption", "tags", "emojis", "file_path",
                                         "sha256", "credit", "ai_generated", "published_at")}


def _filter(rows: list[dict], query: dict[str, list[str]]) -> list[dict]:
    for key, values in query.items():
        if key in ("select", "order", "limit", "offset"):
            continue
        value = values[0]
        if value.startswith("eq."):
            rows = [r for r in rows if str(r.get(key)) == value[3:]]
    order = (query.get("order") or [""])[0]
    if order:
        field, _, direction = order.partition(".")
        rows = sorted(rows, key=lambda r: str(r.get(field) or ""), reverse=direction == "desc")
    offset = int((query.get("offset") or ["0"])[0])
    limit = int((query.get("limit") or ["1000"])[0])
    return rows[offset:offset + limit]


class Handler(BaseHTTPRequestHandler):
    def _json(self, payload, status: int = 200) -> None:
        body = json.dumps(payload).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self) -> None:  # noqa: N802
        url = urlparse(self.path)
        query = parse_qs(url.query)
        if url.path == "/rest/v1/discover_stickers":
            return self._json(_filter([_public(s) for s in FEED.get("stickers", [])], query))
        if url.path == "/rest/v1/discover_packs":
            packs = [{**{k: v for k, v in p.items() if k != "sticker_ids"}, "sticker_count": len(p.get("sticker_ids", []))}
                     for p in FEED.get("packs", [])]
            return self._json(_filter(packs, query))
        prefix = "/storage/v1/object/public/stickers/"
        if url.path.startswith(prefix):
            file = (ROOT / "stickers" / url.path[len(prefix):]).resolve()
            if ROOT.resolve() in file.parents and file.is_file():
                data = file.read_bytes()
                self.send_response(200)
                self.send_header("Content-Type", "image/webp")
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)
                return
        self._json({"message": "not found"}, 404)

    def do_POST(self) -> None:  # noqa: N802
        length = int(self.headers.get("Content-Length") or 0)
        args = json.loads(self.rfile.read(length) or b"{}")
        stickers = {s["id"]: s for s in FEED.get("stickers", [])}
        if self.path == "/rest/v1/rpc/discover_pack_stickers":
            pack = next((p for p in FEED.get("packs", []) if p["id"] == args.get("p_pack")), None)
            return self._json([_public(stickers[i]) for i in (pack or {}).get("sticker_ids", []) if i in stickers])
        if self.path == "/rest/v1/rpc/search_stickers":
            q = str(args.get("q", "")).lower()
            adult = bool(args.get("include_adult"))
            hits = [s for s in stickers.values()
                    if (adult or s.get("rating") == "general")
                    and q in " ".join([s.get("caption") or "", " ".join(s.get("tags") or []), s.get("category") or ""]).lower()]
            return self._json([_public(s) for s in hits[: int(args.get("max_rows", 60))]])
        if self.path == "/rest/v1/rpc/report_content":
            print("REPORT", args, file=sys.stderr)
            self.send_response(204); self.end_headers(); return
        self._json({"message": "not found"}, 404)

    def log_message(self, fmt: str, *values) -> None:
        print("discover:", fmt % values, file=sys.stderr)


def main() -> None:
    global FEED, ROOT
    parser = argparse.ArgumentParser()
    parser.add_argument("feed_dir")
    parser.add_argument("--port", type=int, default=8787)
    a = parser.parse_args()
    ROOT = pathlib.Path(a.feed_dir)
    FEED = json.loads((ROOT / "feed.json").read_text(encoding="utf-8"))
    print(f"serving {len(FEED.get('stickers', []))} stickers / {len(FEED.get('packs', []))} packs on :{a.port}")
    ThreadingHTTPServer(("127.0.0.1", a.port), Handler).serve_forever()


if __name__ == "__main__":
    main()
