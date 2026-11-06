"""Headless smoke test for the StickerDock storefront: real browser, no JS errors, UI reacts.
Serves site/ locally and stubs the Supabase REST calls so the test is offline and deterministic."""
import http.server, socketserver, threading, sys, json, time
from pathlib import Path
from playwright.sync_api import sync_playwright

SITE = Path(__file__).resolve().parent

class Handler(http.server.SimpleHTTPRequestHandler):
    def __init__(self, *a, **k): super().__init__(*a, directory=str(SITE), **k)
    def log_message(self, *a): pass

def serve():
    socketserver.TCPServer.allow_reuse_address = True
    httpd = socketserver.TCPServer(("127.0.0.1", 0), Handler)
    port = httpd.server_address[1]
    threading.Thread(target=httpd.serve_forever, daemon=True).start()
    return httpd, port

PACKS = [
    {"id": "p1", "title": "Monday Mood", "description": "office memes", "kind": "static", "rating": "general",
     "category": "office", "sticker_count": 5, "cover_path": "x.webp", "updated_at": "2026-11-07T10:00:00+00:00"},
    {"id": "p2", "title": "Adult Laughs", "description": "savage", "kind": "static", "rating": "adult",
     "category": "dark", "sticker_count": 4, "cover_path": "y.webp", "updated_at": "2020-01-01T00:00:00+00:00"},
]
STICKERS = [
    {"id": "s1", "caption": "kya baat hai", "kind": "static", "rating": "general", "category": "reactions",
     "tags": ["wow"], "emojis": [], "file_path": "a.webp", "published_at": "2026-11-07T10:00:00+00:00"},
]

def main():
    httpd, port = serve()
    base = f"http://127.0.0.1:{port}/index.html"
    errors, failures = [], []
    with sync_playwright() as p:
        browser = p.chromium.launch()
        page = browser.new_page()
        page.on("console", lambda m: errors.append(m.text) if m.type == "error" else None)
        page.on("pageerror", lambda e: errors.append(f"pageerror: {e}"))

        def route(r):
            u = r.request.url
            if "/rest/v1/discover_packs" in u: r.fulfill(status=200, content_type="application/json", body=json.dumps(PACKS))
            elif "/rest/v1/discover_stickers" in u: r.fulfill(status=200, content_type="application/json", body=json.dumps(STICKERS))
            elif "/rest/v1/rpc/discover_pack_stickers" in u: r.fulfill(status=200, content_type="application/json", body=json.dumps(STICKERS))
            elif "supabase.co" in u or "fonts." in u: r.fulfill(status=200, content_type="application/json", body="[]")
            else: r.continue_()
        page.route("**/*", route)

        page.goto(base, wait_until="networkidle")
        page.wait_for_timeout(400)

        # 1) Packs render (general only by default; adult hidden)
        cards = page.query_selector_all(".pack")
        if len(cards) != 1: failures.append(f"expected 1 general pack, got {len(cards)}")
        # 2) NEW badge present on the fresh pack
        if page.query_selector(".chip.new") is None: failures.append("NEW badge missing on fresh pack")
        # 3) 18+ toggle reveals the adult pack
        page.click("label.adult"); page.wait_for_timeout(200)
        if len(page.query_selector_all(".pack")) != 2: failures.append("18+ toggle did not reveal adult pack")
        page.click("label.adult"); page.wait_for_timeout(150)
        # 4) Search filters
        page.fill("#q", "monday"); page.wait_for_timeout(300)
        if len(page.query_selector_all(".pack")) != 1: failures.append("search did not filter to 1")
        page.fill("#q", "zzzznomatch"); page.wait_for_timeout(300)
        if page.query_selector(".state") is None: failures.append("no empty-state on no-match search")
        page.fill("#q", ""); page.wait_for_timeout(250)
        # 5) Open a pack -> sheet + URL deep link + copy button
        page.click(".pack"); page.wait_for_timeout(300)
        if page.query_selector(".sheet") is None: failures.append("pack sheet did not open")
        if "pack=p1" not in page.url: failures.append(f"deep link not set: {page.url}")
        if page.query_selector(".sheet .copy") is None: failures.append("copy-link button missing")
        page.click(".sheet .close"); page.wait_for_timeout(200)  # close before interacting with the page again
        if page.query_selector(".sheet") is not None: failures.append("sheet did not close")
        # 6) Stickers tab
        page.click("#tab-stickers"); page.wait_for_timeout(300)
        if len(page.query_selector_all("figure.tile")) < 1: failures.append("stickers tab rendered none")
        # 7) Deep-link load: fresh page at ?pack=p1 opens the sheet
        page2 = browser.new_page(); page2.route("**/*", route)
        page2.goto(base + "?pack=p1", wait_until="networkidle"); page2.wait_for_timeout(500)
        if page2.query_selector(".sheet") is None: failures.append("?pack= deep link did not open sheet on load")
        browser.close()
    httpd.shutdown()
    hard = [e for e in errors if "favicon" not in e.lower()]
    print("CONSOLE ERRORS:", hard if hard else "none")
    print("FAILURES:", failures if failures else "none")
    sys.exit(1 if (hard or failures) else 0)

main()
