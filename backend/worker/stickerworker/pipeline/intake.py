"""Push a local folder (e.g. a HandNavigate desktop export) into `source_images`.

Dedupes by sha256 (database + within the folder), skips takedown fingerprints,
trusts magic bytes rather than extensions, and uploads originals to the private
`sources` bucket at `<site>/<sha[:2]>/<sha>.<ext>`. Optional metadata per file in a
sidecar `<file>.json` or `<stem>.json`: source_url, page_url, author, license,
topic_hint, site.
"""
from __future__ import annotations

import hashlib
import json
import re
from collections import Counter
from pathlib import Path
from typing import Any

from ..imaging.decode import EXTENSIONS, IMAGE_MIMES, VIDEO_MIMES, DecodeError, decode_image, sniff_mime
from ..store.base import SOURCES_BUCKET, Store
from ..textutil import clean_text
from .fingerprints import TakedownIndex

SUPPORTED_SUFFIXES = {".png", ".jpg", ".jpeg", ".webp", ".gif", ".mp4", ".webm"}
MAX_BYTES = 10_485_760  # sources bucket limit
SIDECAR_MAX = 64_000
TEXT_KEYS = ("author", "license", "topic_hint")
URL_KEYS = ("source_url", "page_url")


def iter_media(folder: Path) -> list[Path]:
    return sorted(p for p in folder.rglob("*") if p.is_file() and p.suffix.lower() in SUPPORTED_SUFFIXES
                  and not any(part.startswith(".") for part in p.relative_to(folder).parts))


def clean_site(value: str) -> str:
    site = re.sub(r"[^a-z0-9_-]+", "-", (value or "").strip().lower()).strip("-")
    return site[:40] or "upload"


def sidecar(path: Path) -> dict[str, str]:
    for candidate in (path.with_name(path.name + ".json"), path.with_suffix(".json")):
        if candidate.is_file() and candidate.stat().st_size <= SIDECAR_MAX:
            try:
                raw = json.loads(candidate.read_text(encoding="utf-8"))
            except (OSError, ValueError):
                return {}
            if not isinstance(raw, dict):
                return {}
            meta = {k: clean_text(raw[k], 300) for k in TEXT_KEYS if isinstance(raw.get(k), str)}
            meta |= {k: raw[k].strip()[:1000] for k in URL_KEYS
                     if isinstance(raw.get(k), str) and raw[k].strip().startswith(("https://", "http://"))}
            if isinstance(raw.get("site"), str):
                meta["site"] = clean_site(raw["site"])
            return {k: v for k, v in meta.items() if v}
    return {}


def file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def name_hint(path: Path) -> str:
    words = [w for w in re.split(r"[\s_.\-()]+", path.stem) if w and not w.isdigit()]
    return clean_text(" ".join(words), 120)


def probe(data: bytes, mime: str) -> dict[str, Any]:
    if mime in VIDEO_MIMES:
        return {"width": None, "height": None, "frames": 1, "duration_ms": 0}
    decoded = decode_image(data)
    return {"width": decoded.width, "height": decoded.height, "frames": decoded.frames,
            "duration_ms": decoded.duration_ms}


def intake_folder(store: Store, folder: Path, *, site: str = "upload", collector: str = "admin",
                  topic: str | None = None, dry_run: bool = False) -> dict[str, Any]:
    folder = Path(folder)
    if not folder.is_dir():
        raise FileNotFoundError(f"not a folder: {folder}")
    counts: Counter[str] = Counter()
    entries: list[tuple[Path, str]] = []  # hashes only: large exports never sit in memory at once
    for path in iter_media(folder):
        size = path.stat().st_size
        if not 12 <= size <= MAX_BYTES:
            counts["skipped_size"] += 1
            continue
        entries.append((path, file_sha256(path)))
    existing = store.existing_sha256([sha for _, sha in entries]) if entries else set()
    takedowns = TakedownIndex(store.takedowns()) if entries else TakedownIndex([])
    seen: set[str] = set()
    for path, sha in entries:
        if sha in existing or sha in seen:
            counts["skipped_duplicate"] += 1
            continue
        seen.add(sha)
        meta = sidecar(path)
        if takedowns.match(sha256=sha, url=meta.get("source_url")):
            counts["skipped_takedown"] += 1
            continue
        data = path.read_bytes()
        if hashlib.sha256(data).hexdigest() != sha:  # file changed while we were working
            counts["skipped_changed"] += 1
            continue
        mime = sniff_mime(data)
        if mime not in IMAGE_MIMES | VIDEO_MIMES:
            counts["skipped_unsupported"] += 1
            continue
        try:
            info = probe(data, mime)
        except DecodeError:
            counts["skipped_invalid"] += 1
            continue
        source_site = clean_site(meta.pop("site", site))
        sidecar_topic = meta.pop("topic_hint", None)
        hint = clean_text(topic, 120) if topic else sidecar_topic or name_hint(path) or None
        storage_path = f"{source_site}/{sha[:2]}/{sha}.{EXTENSIONS[mime]}"
        if dry_run:
            counts["planned"] += 1
            continue
        store.upload(SOURCES_BUCKET, storage_path, data, mime)
        row = store.insert_source({
            "sha256": sha, "storage_path": storage_path, "mime": mime, "bytes": len(data), "site": source_site,
            "collector": clean_text(collector, 40) or "admin", "topic_hint": hint, **info, **meta})
        counts["inserted" if row else "skipped_duplicate"] += 1
    return {"files": len(entries) + counts["skipped_size"], **counts}
