"""Stage 0 (`trends`): a small India brief from Google Trends IN and PIB RSS, plus owner topics.

Only titles, keywords and links are stored; never article bodies or images.
Tragedies and protected people are skipped. Fetched trends older than 48 h are
deactivated; owner topics (source=owner) are left for the owner to manage.
"""
from __future__ import annotations

import html
import math
import re
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta
from email.utils import parsedate_to_datetime
from typing import Any

import httpx

from ..policy import mentions_protected
from ..textutil import clean_text, tokens, unique
from ..timeutil import ist_day
from .context import Context

GOOGLE_TRENDS_IN = "https://trends.google.com/trending/rss?geo=IN"
PIB_RSS = "https://www.pib.gov.in/ViewRss.aspx?lang=1&reg=1"
TRENDS_NS = "{https://trends.google.com/trending/rss}"
MAX_FEED_BYTES = 2_000_000
WINDOW = timedelta(hours=48)
FETCHED_SOURCES = ("google_trends_in", "pib")
PIB_LIMIT = 10

SENSITIVE = frozenset({
    "death", "dead", "dies", "died", "killed", "kill", "murder", "murdered", "rape", "raped", "assault",
    "accident", "crash", "collapse", "blast", "explosion", "attack", "terror", "terrorist", "shooting",
    "suicide", "stampede", "flood", "floods", "earthquake", "cyclone", "riot", "riots", "tragedy", "victim",
    "victims", "injured", "toll", "funeral", "obituary", "condolence", "condolences", "war", "hostage", "missing",
})
SENSITIVE_PHRASES = ("passes away", "passed away", "rip ")
CATEGORY_WORDS = {
    "festivals": {"diwali", "holi", "eid", "christmas", "navratri", "durga", "puja", "chhath", "dussehra", "ganesh",
                  "onam", "pongal", "lohri", "rakhi", "janmashtami", "karwa", "chauth", "festival", "bhaidooj"},
    "movies": {"movie", "film", "trailer", "teaser", "boxoffice", "bollywood", "ott", "release", "series"},
    "songs": {"song", "album", "music", "concert", "singer"},
}
_STOP = {"the", "and", "for", "with", "from", "after", "about", "this", "that", "will", "has", "have", "are", "was",
         "new", "news", "today", "live", "update", "updates", "latest", "india", "indian", "says", "said", "over"}


@dataclass(frozen=True)
class TrendItem:
    title: str
    summary: str | None
    keywords: tuple[str, ...]
    category: str
    source: str
    source_urls: tuple[str, ...]
    score: float


class FeedError(ValueError):
    pass


def fetch(client: httpx.Client, url: str) -> bytes:
    with client.stream("GET", url) as response:
        if response.status_code != 200:
            raise FeedError(f"HTTP {response.status_code}")
        chunks, size = [], 0
        for chunk in response.iter_bytes():
            size += len(chunk)
            if size > MAX_FEED_BYTES:
                raise FeedError("feed too large")
            chunks.append(chunk)
    return b"".join(chunks)


def parse_xml(data: bytes) -> ET.Element:
    head = data[:4096].lower()
    if b"<!doctype" in head or b"<!entity" in data.lower():
        raise FeedError("DTDs are not accepted")
    try:
        return ET.fromstring(data)
    except ET.ParseError:
        raise FeedError("invalid XML") from None


def _published(value: str | None, now: datetime) -> datetime:
    if not value:
        return now
    try:
        stamp = parsedate_to_datetime(value)
    except (TypeError, ValueError):
        return now
    return stamp if stamp.tzinfo else stamp.replace(tzinfo=UTC)


def parse_traffic(value: str | None) -> float:
    match = re.match(r"\s*([\d,.]+)\s*([KkMm]?)", value or "")
    if not match:
        return 0.0
    number = float(match.group(1).replace(",", "") or 0)
    return number * {"k": 1e3, "m": 1e6}.get(match.group(2).lower(), 1.0)


def is_sensitive(text: str) -> bool:
    lowered = f" {text.lower()} "
    return bool(set(tokens(text)) & SENSITIVE) or any(p in lowered for p in SENSITIVE_PHRASES)


def category_for(text: str, allowed: tuple[str, ...]) -> str:
    words = set(tokens(text))
    for category, vocabulary in CATEGORY_WORDS.items():
        if words & vocabulary and category in allowed:
            return category
    return "trending" if "trending" in allowed else allowed[0]


def keywords_for(title: str, headlines: list[str]) -> tuple[str, ...]:
    words = [w for w in tokens(" ".join([title, *headlines])) if len(w) >= 3 and w not in _STOP and not w.isdigit()]
    return unique([title.lower(), *words], 10)


def _https(url: str | None) -> str | None:
    text = (url or "").strip()
    if text.startswith("http://pib.gov.in") or text.startswith("http://www.pib.gov.in"):
        text = "https://" + text[len("http://"):]
    return text[:1000] if text.startswith("https://") else None


def parse_google_trends(data: bytes, now: datetime, allowed: tuple[str, ...]) -> list[TrendItem]:
    items: list[TrendItem] = []
    for entry in parse_xml(data).iter("item"):
        query = clean_text(html.unescape(entry.findtext("title") or ""), 100)
        if not query or now - _published(entry.findtext("pubDate"), now) > WINDOW:
            continue
        headlines, urls = [], []
        for news in entry.findall(f"{TRENDS_NS}news_item")[:3]:
            title = clean_text(html.unescape(news.findtext(f"{TRENDS_NS}news_item_title") or ""), 160)
            url = _https(news.findtext(f"{TRENDS_NS}news_item_url"))
            if title:
                headlines.append(title)
            if url:
                urls.append(url)
        text = " ".join([query, *headlines])
        if is_sensitive(text):
            continue
        traffic = parse_traffic(entry.findtext(f"{TRENDS_NS}approx_traffic"))
        items.append(TrendItem(query, " | ".join(headlines[:2])[:240] or None, keywords_for(query, headlines),
                               category_for(text, allowed), "google_trends_in", tuple(urls[:3]),
                               round(math.log10(traffic + 1), 2) if traffic else 1.0))
    return items


def parse_pib(data: bytes, now: datetime, allowed: tuple[str, ...]) -> list[TrendItem]:
    items: list[TrendItem] = []
    for entry in parse_xml(data).iter("item"):
        title = clean_text(html.unescape(entry.findtext("title") or ""), 160)
        if not title or is_sensitive(title) or now - _published(entry.findtext("pubDate"), now) > WINDOW:
            continue
        url = _https(entry.findtext("link"))
        items.append(TrendItem(title, None, keywords_for(title, []), category_for(title, allowed), "pib",
                               (url,) if url else (), 0.5))
        if len(items) >= PIB_LIMIT:
            break
    return items


def to_row(item: TrendItem, day: str) -> dict[str, Any]:
    return {"day": day, "title": item.title, "summary": item.summary, "keywords": list(item.keywords),
            "category": item.category, "source": item.source, "source_urls": list(item.source_urls),
            "score": item.score, "active": True}


def run(ctx: Context, limit: int = 0, *, client: httpx.Client | None = None, offline: bool = False) -> dict[str, Any]:
    now = ctx.now()
    summary: dict[str, Any] = {}
    items: list[TrendItem] = []
    if offline:
        summary["offline"] = True
    else:
        http = client or httpx.Client(timeout=20.0, follow_redirects=True, max_redirects=3,
                                      headers={"User-Agent": "StickerDockWorker/0.1 (+trend titles only)"})
        allowed = ctx.settings.categories
        for name, url, parser in (("google_trends_in", GOOGLE_TRENDS_IN, parse_google_trends),
                                  ("pib", PIB_RSS, parse_pib)):
            try:
                found = parser(fetch(http, url), now, allowed)
            except (httpx.HTTPError, FeedError) as error:
                summary[f"{name}_error"] = f"{type(error).__name__}: {error}"[:200]
                continue
            items += found
            summary[name] = len(found)
    seen: set[str] = set()
    rows = []
    for item in items:
        key = item.title.casefold()
        if key in seen or mentions_protected(item.title, ctx.protected):
            continue
        seen.add(key)
        rows.append(to_row(item, ist_day(now).isoformat()))
    summary["upserted"] = ctx.store.upsert_trends(rows)
    summary["deactivated"] = ctx.store.deactivate_trends(now - WINDOW, FETCHED_SOURCES)
    summary["active"] = len(ctx.store.active_trends(now - WINDOW))
    return summary
