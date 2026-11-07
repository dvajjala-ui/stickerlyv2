"""Trend brief: Google Trends IN + PIB RSS parsing, filters, 48 h window; no network."""
from __future__ import annotations

from datetime import UTC, datetime, timedelta

import httpx
import pytest

from stickerworker.pipeline import trends
from stickerworker.timeutil import iso

NOW = datetime(2026, 11, 5, 6, 0, tzinfo=UTC)
ALLOWED = ("reactions", "movies", "festivals", "trending")

GOOGLE = b"""<?xml version="1.0" encoding="UTF-8"?>
<rss xmlns:ht="https://trends.google.com/trending/rss" version="2.0"><channel><title>Daily Search Trends</title>
<item><title>ind vs aus</title><ht:approx_traffic>200K+</ht:approx_traffic>
  <pubDate>Thu, 5 Nov 2026 03:10:00 +0000</pubDate>
  <ht:picture>https://example.com/never-copied.jpg</ht:picture>
  <ht:news_item><ht:news_item_title>India beat Australia in a thriller</ht:news_item_title>
    <ht:news_item_url>https://news.example.com/cricket/1</ht:news_item_url>
    <ht:news_item_picture>https://example.com/pic.jpg</ht:news_item_picture></ht:news_item>
  <ht:news_item><ht:news_item_title>Kohli&amp;#39;s century seals series</ht:news_item_title>
    <ht:news_item_url>http://insecure.example.com/2</ht:news_item_url></ht:news_item>
</item>
<item><title>plane crash</title><ht:approx_traffic>500K+</ht:approx_traffic>
  <pubDate>Thu, 5 Nov 2026 02:00:00 +0000</pubDate></item>
<item><title>old topic</title><ht:approx_traffic>2,000+</ht:approx_traffic>
  <pubDate>Sun, 1 Nov 2026 02:00:00 +0000</pubDate></item>
<item><title>diwali sale</title><ht:approx_traffic>50K+</ht:approx_traffic>
  <pubDate>Thu, 5 Nov 2026 01:00:00 +0000</pubDate></item>
<item><title>Amitabh Bachchan new film</title><ht:approx_traffic>100K+</ht:approx_traffic>
  <pubDate>Thu, 5 Nov 2026 01:00:00 +0000</pubDate></item>
</channel></rss>"""

PIB = b"""<?xml version="1.0" encoding="utf-8"?><rss version="2.0"><channel><title>PIB</title>
<item><title>Cabinet approves new rail line</title><link>http://pib.gov.in/PressReleasePage.aspx?PRID=1</link>
  <description>Long article body that must never be stored.</description></item>
<item><title>PM condoles loss of lives in accident</title><link>https://pib.gov.in/x</link></item>
</channel></rss>"""


def test_google_trends_keeps_titles_keywords_and_links_only():
    items = trends.parse_google_trends(GOOGLE, NOW, ALLOWED)
    titles = [i.title for i in items]
    assert titles == ["ind vs aus", "diwali sale", "Amitabh Bachchan new film"]  # crash + stale dropped
    cricket = items[0]
    assert cricket.keywords[0] == "ind vs aus" and "australia" in cricket.keywords
    assert cricket.summary == "India beat Australia in a thriller | Kohli's century seals series"
    assert cricket.source_urls == ("https://news.example.com/cricket/1",)  # http and pictures dropped
    assert cricket.score == pytest.approx(5.3, abs=0.01)
    assert items[1].category == "festivals"


def test_pib_titles_only_and_https():
    items = trends.parse_pib(PIB, NOW, ALLOWED)
    assert [i.title for i in items] == ["Cabinet approves new rail line"]
    assert items[0].source_urls == ("https://pib.gov.in/PressReleasePage.aspx?PRID=1",)
    assert items[0].summary is None


def test_dtd_payloads_are_refused():
    with pytest.raises(trends.FeedError):
        trends.parse_xml(b'<?xml version="1.0"?><!DOCTYPE x [<!ENTITY a "aaaa">]><rss>&a;</rss>')


def test_traffic_and_sensitivity_helpers():
    assert trends.parse_traffic("200K+") == 200_000
    assert trends.parse_traffic("2,000+") == 2000
    assert trends.parse_traffic("1M+") == 1_000_000
    assert trends.is_sensitive("Actor passes away at 80")
    assert not trends.is_sensitive("New award for best film")


def test_stage_upserts_filters_protected_and_expires_old(ctx, store):
    store.db.execute("insert into trends (id, day, title, keywords, source, source_urls, score, active, created_at)"
                     " values ('old', '2026-11-01', 'stale', '[]', 'google_trends_in', '[]', 1, 1, ?)",
                     (iso(NOW - timedelta(days=4)),))
    store.db.execute("insert into trends (id, day, title, keywords, source, source_urls, score, active, created_at)"
                     " values ('mine', '2026-10-01', 'owner topic', '[]', 'owner', '[]', 9, 1, ?)",
                     (iso(NOW - timedelta(days=30)),))
    store.db.commit()
    ctx.now = lambda: NOW
    store._now = lambda: NOW

    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, content=GOOGLE if request.url.host == "trends.google.com" else PIB)

    summary = trends.run(ctx, client=httpx.Client(transport=httpx.MockTransport(handler)))
    assert summary["google_trends_in"] == 3 and summary["pib"] == 1
    assert summary["upserted"] == 3  # protected-person trend skipped
    assert summary["deactivated"] == 1
    active = {t["title"]: t for t in store.active_trends(NOW - timedelta(hours=48))}
    assert set(active) == {"ind vs aus", "diwali sale", "Cabinet approves new rail line", "owner topic"}
    assert active["ind vs aus"]["day"] == "2026-11-05"
    assert "never-copied" not in str(active)


def test_stage_survives_feed_errors(ctx):
    client = httpx.Client(transport=httpx.MockTransport(lambda request: httpx.Response(503)))
    summary = trends.run(ctx, client=client)
    assert "google_trends_in_error" in summary and "pib_error" in summary and summary["upserted"] == 0


def test_offline_mode_never_fetches(ctx):
    assert trends.run(ctx, offline=True)["offline"] is True


def test_upsert_does_not_reactivate_an_owner_disabled_trend(store):
    from stickerworker.pipeline.trends import to_row
    row = to_row(type("T", (), {"title": "chai meme", "summary": "", "keywords": ["chai"],
                                "category": "funny", "source": "owner", "source_urls": [], "score": 5.0})(), "2026-11-07")
    store.upsert_trends([row])
    # owner switches it off
    store.db.execute("update trends set active = 0 where title = 'chai meme'")
    # the next trends run upserts the same (day,title)
    store.upsert_trends([row])
    got = store._select("trends", "select active from trends where title = 'chai meme'")
    assert got and int(got[0]["active"]) == 0, "an owner-disabled trend must stay off after re-upsert"
