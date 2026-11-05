"""AI providers: Groq (production) and a deterministic FakeAI (offline runs and tests)."""
from __future__ import annotations

from collections.abc import Callable
from typing import Any

from ..config import Env
from .base import (
    AI,
    AIError,
    AIInvalid,
    AIUnavailable,
    CaptionRequest,
    PackName,
    PackNameRequest,
    TrendBrief,
    VisionInput,
)
from .budget import Budget, BudgetExhausted
from .fake import FakeAI
from .groq import GroqClient
from .groq_ai import GroqAI

__all__ = ["AI", "AIError", "AIInvalid", "AIUnavailable", "Budget", "BudgetExhausted", "CaptionRequest", "FakeAI",
           "GroqAI", "PackName", "PackNameRequest", "TrendBrief", "VisionInput", "make_groq"]


def make_groq(env: Env, budget: Budget, ledger: Callable[[dict[str, Any]], None]) -> GroqAI:
    client = GroqClient(env.groq_keys, budget=budget, ledger=ledger, max_wait_s=env.max_wait_s)
    return GroqAI(client, env)
