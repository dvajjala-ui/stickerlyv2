"""Pack composition: 30 cap, growing volumes, no static/animated or adult/general mixing, trends."""
from __future__ import annotations

import hashlib
import itertools

from samples import photo

from stickerworker.analysis import Crop
from stickerworker.imaging import compose
from stickerworker.imaging.encode import encode_static
from stickerworker.imaging.validate import inspect_tray
from stickerworker.pipeline import packs

_counter = itertools.count()
WEBP = encode_static(compose.direct(photo(seed=9), Crop()).image)


def add_sticker(store, *, category="office", kind="static", rating="general", quality=7, status="published",
                tags=("office", "boss"), caption="Kya scene hai", trend_id=None):
    n = next(_counter)
    sticker_id = f"00000000-0000-4000-8000-{n:012d}"
    store.upload("stickers", f"{sticker_id}.webp", WEBP, "image/webp")
    return store.insert_sticker({
        "id": sticker_id, "recipe": "caption", "kind": kind, "rating": rating, "category": category,
        "caption": caption, "tags": list(tags), "emojis": [], "file_path": f"{sticker_id}.webp",
        "sha256": hashlib.sha256(sticker_id.encode()).hexdigest(), "bytes": len(WEBP), "quality": quality,
        "status": status, "trend_id": trend_id})


def pack_members(store):
    rows = store.db.execute("select p.id, p.kind, p.rating, p.category, s.kind, s.rating, s.category "
                            "from pack_items i join packs p on p.id = i.pack_id join stickers s on s.id = i.sticker_id")
    return rows.fetchall()


def test_series_grow_to_30_then_open_new_volumes(ctx, store):
    for i in range(65):
        add_sticker(store, quality=5 + i % 5)
    summary = packs.run(ctx)
    series = sorted((p for p in store.packs() if p["pack_type"] == "series"), key=lambda p: p["volume"])
    assert [p["volume"] for p in series] == [1, 2, 3]
    assert [p["sticker_count"] for p in series] == [30, 30, 5]
    assert series[0]["title"] == "Office Ke Dukh" and series[1]["title"] == "Office Ke Dukh Vol 2"
    assert all(p["status"] == "published" and p["published_at"] for p in series)
    assert summary["series_added"] == 65 and summary["packs_created"] == 3
    for pack in series:  # cover = best quality member
        members = store.rows("stickers", "id in (select sticker_id from pack_items where pack_id = ?)", (pack["id"],))
        assert pack["cover_sticker_id"] in {m["id"] for m in members if m["quality"] == max(x["quality"]
                                                                                             for x in members)}
        assert inspect_tray(store.download("stickers", f"trays/{pack['id']}.png")) == ()


def test_never_mixes_kind_or_rating(ctx, store):
    for kind, rating in itertools.product(("static", "animated"), ("general", "adult")):
        for _ in range(4):
            add_sticker(store, kind=kind, rating=rating)
    packs.run(ctx)
    assert len(store.packs()) == 4
    for _, p_kind, p_rating, p_cat, s_kind, s_rating, s_cat in pack_members(store):
        assert (p_kind, p_rating, p_cat) == (s_kind, s_rating, s_cat)
    adult = [p for p in store.packs() if p["rating"] == "adult"]
    assert all(p["title"].startswith("18+") for p in adult)


def test_compose_is_idempotent(ctx, store):
    for _ in range(5):
        add_sticker(store)
    packs.run(ctx)
    versions = {p["id"]: p["version"] for p in store.packs()}
    again = packs.run(ctx)
    assert again["series_added"] == 0 and again["packs_created"] == 0 and again["packs_updated"] == 0
    assert {p["id"]: p["version"] for p in store.packs()} == versions


def test_small_groups_stay_draft(ctx, store):
    add_sticker(store, category="festivals")
    add_sticker(store, category="festivals")
    packs.run(ctx)
    (pack,) = store.packs()
    assert pack["status"] == "draft" and pack["sticker_count"] == 2


def test_taken_down_stickers_are_pruned_and_versions_bump(ctx, store):
    stickers = [add_sticker(store) for _ in range(4)]
    packs.run(ctx)
    (pack,) = store.packs()
    store.take_down(stickers[0]["id"], "copyright")
    packs.run(ctx)
    (after,) = store.packs()
    assert after["sticker_count"] == 3 and after["version"] > pack["version"]
    remaining = {i["sticker_id"] for i in store.pack_items([after["id"]])}
    assert stickers[0]["id"] not in remaining


def test_positions_stay_unique_after_pruning(ctx, store):
    stickers = [add_sticker(store) for _ in range(5)]
    packs.run(ctx)
    store.take_down(stickers[1]["id"], "report")
    add_sticker(store)
    packs.run(ctx)
    (pack,) = store.packs()
    positions = [item["position"] for item in store.pack_items([pack["id"]])]
    assert len(positions) == len(set(positions)) == 5


def test_held_stickers_are_never_packed_or_counted_towards_three(ctx, store):
    for _ in range(2):
        add_sticker(store, category="festivals")
    held = add_sticker(store, category="festivals", status="held")
    packs.run(ctx)
    (pack,) = store.packs()
    assert pack["status"] == "draft" and pack["sticker_count"] == 2
    assert held["id"] not in {i["sticker_id"] for i in store.pack_items([pack["id"]])}
    store.update_sticker(held["id"], {"status": "published"})  # an admin approves it
    packs.run(ctx)
    (pack,) = store.packs()
    assert pack["status"] == "published" and pack["sticker_count"] == 3


def test_daily_trend_pack_needs_six_matches(ctx, store):
    store.upsert_trends([{"title": "World Cup Final", "keywords": ["world cup", "cricket", "final"],
                          "source": "owner", "score": 5.0, "category": "trending"}])
    for _ in range(5):
        add_sticker(store, tags=("cricket", "final", "match"), caption="Kya shot hai")
    packs.run(ctx)
    assert not [p for p in store.packs() if p["pack_type"] == "daily"]
    add_sticker(store, tags=("world cup", "cricket"), caption="Final mein dil toot gaya")
    packs.run(ctx)
    (daily,) = [p for p in store.packs() if p["pack_type"] == "daily"]
    assert daily["sticker_count"] == 6 and daily["status"] == "published"
    assert daily["title"].startswith("Trending: World Cup Final")
