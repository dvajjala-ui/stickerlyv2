"""Stage 4 (`compose`): build packs from published stickers.

- Series packs per (category, kind, rating) are add-ready sets: they publish at >= 8 stickers and
  grow to 12, then volume+1 opens (owner decision 2026-11-11: packs of 8-12, not random singles).
- Daily drops: an active trend with >= 6 matching stickers gets a `daily` pack (also capped at 12).
- kind and rating are part of every pack key, so static/animated and general/adult
  never mix. Non-published stickers are pruned from managed packs (and re-added
  if they are published again).
"""
from __future__ import annotations

from collections import defaultdict
from dataclasses import dataclass, field
from datetime import timedelta
from typing import Any

from ..ai.base import AIError, PackName, PackNameRequest
from ..imaging.decode import decode_image
from ..imaging.encode import EncodeError, encode_tray
from ..lexicon import lexical_flags
from ..naming import fallback_pack_name, volume_title
from ..policy import mentions_protected
from ..store.base import STICKERS_BUCKET, Row, StoreError
from ..textutil import slugify, tokens
from ..timeutil import iso
from .context import Context

CAPACITY = 12
SERIES_MIN = 8
DAILY_MIN = 6
MANAGED_TYPES = ("series", "daily")
FROZEN_STATUSES = ("taken_down", "archived")
_STOP = {"the", "and", "for", "with", "new", "news", "today", "india", "indian", "live", "update", "updates",
         "latest", "from", "will", "after", "about", "what", "this", "that", "hai", "kya", "aur", "bhi", "over"}


@dataclass
class PackState:
    row: Row
    items: list[str]
    added: list[str] = field(default_factory=list)
    next_position: int = 0

    @property
    def id(self) -> str:
        return self.row["id"]

    @property
    def key(self) -> tuple[str | None, str, str]:
        return self.row.get("series"), self.row["kind"], self.row["rating"]

    @property
    def frozen(self) -> bool:
        return self.row.get("status") in FROZEN_STATUSES


def load_packs(ctx: Context) -> list[PackState]:
    rows = [r for r in ctx.store.packs() if r.get("pack_type") in MANAGED_TYPES]
    states = {r["id"]: PackState(r, []) for r in rows}
    for item in sorted(ctx.store.pack_items(list(states)), key=lambda i: i["position"]):
        if item["pack_id"] in states:
            state = states[item["pack_id"]]
            state.items.append(item["sticker_id"])
            state.next_position = max(state.next_position, int(item["position"]) + 1)
    return list(states.values())


def safe_name(name: PackName, rating: str, protected: list[str]) -> bool:
    text = f"{name.title} {name.description}"
    flags = lexical_flags(text)
    if flags & {"explicit", "slur"} or (rating == "general" and flags & {"suggestive", "profanity"}):
        return False
    return mentions_protected(text, protected) is None


def pack_name(ctx: Context, category: str, kind: str, rating: str, samples: list[str], trend: str = "") -> PackName:
    fallback = fallback_pack_name(category, kind, rating, trend)
    if ctx.ai is None or ctx.ai.remaining("text") <= 0:
        return fallback
    try:
        name = ctx.ai.name_pack(PackNameRequest(category, kind, rating, tuple(samples[:10]), trend))
    except AIError as error:
        ctx.log(f"compose: pack title fallback ({error})")
        return fallback
    if not safe_name(name, rating, ctx.protected):
        return fallback
    title = name.title if rating != "adult" or name.title.startswith("18+") else f"18+ {name.title}"
    return PackName(title, name.description or fallback.description)


def create_pack(ctx: Context, packs: list[PackState], *, pack_type: str, series: str, kind: str, rating: str,
                category: str, volume: int, samples: list[str], trend: str = "") -> PackState:
    name = pack_name(ctx, category, kind, rating, samples, trend)
    slug = slugify(f"{series}-{kind}-{rating}-v{volume}", 120)
    row = ctx.store.insert_pack({
        "slug": slug, "title": volume_title(name.title, volume), "description": name.description, "kind": kind,
        "rating": rating, "category": category, "pack_type": pack_type, "series": series, "volume": volume,
        "status": "draft"})
    state = PackState(row, [])
    packs.append(state)
    return state


def _samples(stickers: list[Row], category: str) -> list[str]:
    texts = [s.get("caption") or " ".join((s.get("tags") or [])[:4]) for s in stickers if s.get("category") == category]
    return [t for t in texts if t][:10]


def prune(ctx: Context, packs: list[PackState], info: dict[str, Row]) -> int:
    removed = 0
    for pack in packs:
        stale = [sid for sid in pack.items if info.get(sid, {}).get("status") != "published"]
        if stale and not pack.frozen:
            ctx.store.remove_pack_items(pack.id, stale)
            pack.items = [sid for sid in pack.items if sid not in stale]
            removed += len(stale)
    return removed


def grow_series(ctx: Context, packs: list[PackState], stickers: list[Row]) -> int:
    in_series = {sid for p in packs if p.row["pack_type"] == "series" for sid in p.items}
    added = 0
    for sticker in stickers:
        if sticker["id"] in in_series:
            continue
        key = (sticker["category"], sticker["kind"], sticker["rating"])
        same = [p for p in packs if p.row["pack_type"] == "series" and p.key == key]
        live = [p for p in same if not p.frozen]
        current = max(live, key=lambda p: p.row["volume"]) if live else None
        if current is None or len(current.items) >= CAPACITY:
            volume = max((p.row["volume"] for p in same), default=0) + 1
            current = create_pack(ctx, packs, pack_type="series", series=key[0], kind=key[1], rating=key[2],
                                  category=key[0], volume=volume, samples=_samples(stickers, key[0]))
        current.items.append(sticker["id"])
        current.added.append(sticker["id"])
        in_series.add(sticker["id"])
        added += 1
    return added


def trend_terms(trend: Row) -> tuple[list[str], set[str]]:
    phrases = [p.lower() for p in [trend.get("title") or "", *(trend.get("keywords") or [])] if len(p) >= 4]
    words = {w for p in phrases for w in tokens(p) if len(w) >= 3 and w not in _STOP}
    return phrases, words


def matches_trend(sticker: Row, phrases: list[str], words: set[str]) -> bool:
    text = " ".join([sticker.get("caption") or "", sticker.get("description") or "",
                     " ".join(sticker.get("tags") or [])]).lower()
    if any(p in text for p in phrases):
        return True
    return len(words & set(tokens(text))) >= 2


def daily_drops(ctx: Context, packs: list[PackState], stickers: list[Row]) -> int:
    added = 0
    for trend in ctx.store.active_trends(ctx.now() - timedelta(hours=48)):
        if mentions_protected(trend.get("title") or "", ctx.protected):
            continue  # never title a pack after a protected person
        phrases, words = trend_terms(trend)
        groups: dict[tuple[str, str], list[Row]] = defaultdict(list)
        for sticker in stickers:
            if sticker.get("trend_id") == trend["id"] or matches_trend(sticker, phrases, words):
                groups[(sticker["kind"], sticker["rating"])].append(sticker)
        for (kind, rating), group in groups.items():
            series = f"trend-{trend['id']}"
            pack = next((p for p in packs if p.row["pack_type"] == "daily" and p.key == (series, kind, rating)), None)
            if pack is None:
                if len(group) < DAILY_MIN:
                    continue
                pack = create_pack(ctx, packs, pack_type="daily", series=series, kind=kind, rating=rating,
                                   category=trend.get("category") or "trending", volume=1,
                                   samples=[s.get("caption") or "" for s in group], trend=trend["title"])
            if pack.frozen:
                continue
            for sticker in sorted(group, key=lambda s: -(s.get("quality") or 0)):
                if sticker["id"] not in pack.items and len(pack.items) < CAPACITY:
                    pack.items.append(sticker["id"])
                    pack.added.append(sticker["id"])
                    added += 1
    return added


def upload_tray(ctx: Context, pack_id: str, cover: Row) -> None:
    try:
        image = decode_image(ctx.store.download(STICKERS_BUCKET, cover["file_path"])).image
        ctx.store.upload(STICKERS_BUCKET, f"trays/{pack_id}.png", encode_tray(image), "image/png", cache_seconds=3600)
    except (StoreError, EncodeError, ValueError, OSError) as error:
        ctx.log(f"compose: tray for {pack_id} skipped ({error})")


def finalize(ctx: Context, packs: list[PackState], info: dict[str, Row], refresh: bool) -> dict[str, int]:
    fresh = {r["id"]: r for r in ctx.store.packs()} if refresh else {}
    counts = {"packs_updated": 0, "packs_published": 0}
    for pack in packs:
        row = fresh.get(pack.id, pack.row)
        if row.get("status") in FROZEN_STATUSES:
            continue
        published = [sid for sid in pack.items if info.get(sid, {}).get("status") == "published"]
        cover = max(published, key=lambda sid: (info[sid].get("quality") or 0, -published.index(sid)),
                    default=None)
        fields: dict[str, Any] = {}
        if cover != row.get("cover_sticker_id"):
            fields["cover_sticker_id"] = cover
        if row.get("status") == "draft" and ctx.settings.publish.auto_publish:
            minimum = SERIES_MIN if row.get("pack_type") == "series" else DAILY_MIN
            if len(published) >= minimum:
                fields["status"] = "published"
                fields["published_at"] = row.get("published_at") or iso(ctx.now())
                counts["packs_published"] += 1
        if fields:
            fields["version"] = int(row.get("version") or 1) + 1
            ctx.store.update_pack(pack.id, fields)
            counts["packs_updated"] += 1
            if fields.get("cover_sticker_id"):
                upload_tray(ctx, pack.id, info[cover])
    return counts


def run(ctx: Context, limit: int = 0) -> dict[str, Any]:
    stickers = list(reversed(ctx.store.published_stickers()))  # oldest first
    packs = load_packs(ctx)
    before = len(packs)
    item_ids = sorted({sid for p in packs for sid in p.items})
    info = {s["id"]: s for s in ctx.store.stickers_by_id(item_ids)} if item_ids else {}
    info.update({s["id"]: s for s in stickers})
    pruned = prune(ctx, packs, info)
    series_added = grow_series(ctx, packs, stickers)
    daily_added = daily_drops(ctx, packs, stickers)
    for pack in packs:
        if pack.added:
            ctx.store.add_pack_items([{"pack_id": pack.id, "sticker_id": sid, "position": pack.next_position + i}
                                      for i, sid in enumerate(pack.added)])
    changed = pruned or series_added or daily_added
    counts = finalize(ctx, packs, info, refresh=bool(changed) and ctx.store.kind != "dry-run")
    return {"pruned": pruned, "series_added": series_added, "daily_added": daily_added,
            "packs_created": len(packs) - before, **counts}
