"""What every stage receives: the store, the (optional) AI, typed settings, env and a clock."""
from __future__ import annotations

import sys
from collections.abc import Callable
from dataclasses import dataclass, field
from datetime import datetime

from ..ai.base import AI
from ..config import Env
from ..settings import Settings
from ..store.base import Store
from ..timeutil import ist_day_start, utcnow
from .fingerprints import TakedownIndex


def _stderr(message: str) -> None:
    print(message, file=sys.stderr, flush=True)


@dataclass
class Context:
    store: Store
    settings: Settings
    env: Env
    ai: AI | None = None
    dry_run: bool = False
    now: Callable[[], datetime] = utcnow
    log: Callable[[str], None] = _stderr
    _protected: list[str] | None = field(default=None, repr=False)
    _takedowns: TakedownIndex | None = field(default=None, repr=False)

    @property
    def protected(self) -> list[str]:
        if self._protected is None:
            self._protected = self.store.protected_people()
        return self._protected

    @property
    def takedowns(self) -> TakedownIndex:
        if self._takedowns is None:
            self._takedowns = TakedownIndex(self.store.takedowns())
        return self._takedowns

    def day_start(self) -> datetime:
        return ist_day_start(self.now())
