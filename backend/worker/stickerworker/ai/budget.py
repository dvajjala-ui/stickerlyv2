"""Daily AI budgets from settings.budgets, seeded with today's usage from the ledger.

Units: `vision` counts images (one image ~ 2,048 input tokens), `text` counts requests.
"""
from __future__ import annotations

from dataclasses import dataclass, field

from .base import AIUnavailable


class BudgetExhausted(AIUnavailable):
    def __init__(self, kind: str) -> None:
        self.kind = kind
        super().__init__(f"daily {kind} budget exhausted")


@dataclass
class Budget:
    limits: dict[str, int]
    used: dict[str, int] = field(default_factory=dict)

    def remaining(self, kind: str) -> int:
        return max(0, self.limits.get(kind, 0) - self.used.get(kind, 0))

    def take(self, kind: str, units: int) -> None:
        if units > self.remaining(kind):
            raise BudgetExhausted(kind)
        self.used[kind] = self.used.get(kind, 0) + units

    def refund(self, kind: str, units: int) -> None:
        self.used[kind] = max(0, self.used.get(kind, 0) - units)
