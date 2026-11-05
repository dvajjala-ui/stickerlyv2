"""Takedown fingerprints and near-duplicate lookups (sha256, source URL, pHash Hamming <= 8)."""
from __future__ import annotations

import re
from collections.abc import Iterable, Mapping
from typing import Any

from ..imaging.metrics import hamming

NEAR_DUPLICATE = 8
# Finished stickers share layout (margins, card border, caption style), so their visible-area pHash
# is compared more strictly: re-encodes, re-crops and added borders of one picture land at 0-4 bits.
RENDER_NEAR_DUPLICATE = 6
_PHASH = re.compile(r"^[0-9a-f]{16}$")


def valid_phash(value: Any) -> bool:
    return isinstance(value, str) and bool(_PHASH.match(value))


class TakedownIndex:
    def __init__(self, rows: Iterable[Mapping[str, Any]]) -> None:
        self.sha256: set[str] = set()
        self.urls: set[str] = set()
        self.phashes: list[str] = []
        for row in rows:
            if row.get("sha256"):
                self.sha256.add(str(row["sha256"]).lower())
            if row.get("source_url"):
                self.urls.add(str(row["source_url"]).strip())
            if valid_phash(row.get("phash")):
                self.phashes.append(row["phash"])

    def match(self, *, sha256: str | None = None, phash: str | None = None, url: str | None = None) -> str | None:
        if sha256 and sha256.lower() in self.sha256:
            return "takedown:sha256"
        if url and url.strip() in self.urls:
            return "takedown:source_url"
        if valid_phash(phash) and any(hamming(phash, known) <= NEAR_DUPLICATE for known in self.phashes):
            return "takedown:phash"
        return None


def nearest(phash: str, known: Iterable[tuple[str, str]], exclude: str | None = None,
            limit: int = NEAR_DUPLICATE) -> tuple[str, int] | None:
    """Closest known fingerprint within `limit` bits (default NEAR_DUPLICATE), as (label, distance)."""
    best: tuple[str, int] | None = None
    for label, other in known:
        if label == exclude or not valid_phash(other):
            continue
        distance = hamming(phash, other)
        if distance <= limit and (best is None or distance < best[1]):
            best = (label, distance)
    return best
