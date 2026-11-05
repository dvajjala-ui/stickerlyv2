"""Read-only wrapper: reads hit the real store, writes are recorded, uploads become local previews.

Claims become plain reads, so a dry run never leases, edits or uploads anything.
Each stage sees the store as it is (stages do not see each other's planned writes).
Planned stickers are also counted per status (`stickers_published`, `stickers_held`, ...).
"""
from __future__ import annotations

import uuid
from collections import Counter
from collections.abc import Callable
from pathlib import Path, PurePosixPath
from typing import Any

from .base import Row, Store

_RECORDED = ("update_source", "insert_source", "update_pack", "add_pack_items", "remove_pack_items",
             "upsert_trends", "deactivate_trends", "log_ai_call", "finish_run")


class DryRunStore:
    kind = "dry-run"

    def __init__(self, inner: Store, preview_dir: Path) -> None:
        self.inner = inner
        self.preview_dir = Path(preview_dir)
        self.planned: list[tuple[str, Any]] = []
        self.sticker_statuses: Counter[str] = Counter()

    def __getattr__(self, name: str) -> Any:
        if name in _RECORDED:
            return self._recorder(name)
        return getattr(self.inner, name)

    def _recorder(self, name: str) -> Callable[..., Any]:
        def record(*args: Any, **kwargs: Any) -> Any:
            self.planned.append((name, args[0] if len(args) == 1 else args))
            return 0 if name in ("upsert_trends", "deactivate_trends") else None
        return record

    def claim_sources(self, status: str, next_status: str, limit: int, lease_seconds: int,
                      by_score: bool = False) -> list[Row]:
        return self.inner.peek_sources(status, limit, by_score)

    def upload(self, bucket: str, path: str, data: bytes, content_type: str, cache_seconds: int = 3600) -> None:
        safe = [p for p in PurePosixPath(path).parts if p not in ("..", "/", "")]
        target = self.preview_dir.joinpath(bucket, *safe)
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)
        self.planned.append(("upload", f"{bucket}/{path}"))

    def insert_sticker(self, row: Row) -> Row:
        self.planned.append(("insert_sticker", row.get("file_path")))
        self.sticker_statuses[str(row.get("status") or "draft")] += 1
        return dict(row)

    def insert_pack(self, row: Row) -> Row:
        self.planned.append(("insert_pack", row.get("slug")))
        return {"version": 1, "sticker_count": 0, **row, "id": row.get("id") or str(uuid.uuid4())}

    def start_run(self, stage: str) -> int | None:
        return None

    def summary(self) -> dict[str, int]:
        counts: dict[str, int] = {}
        for name, _ in self.planned:
            counts[name] = counts.get(name, 0) + 1
        counts.update({f"stickers_{status}": n for status, n in sorted(self.sticker_statuses.items())})
        return counts
