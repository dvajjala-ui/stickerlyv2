"""AI provider protocol and the request/response types shared by Groq and FakeAI."""
from __future__ import annotations

from collections.abc import Sequence
from dataclasses import dataclass
from typing import Protocol

from ..analysis import Analysis, Caption


class AIError(RuntimeError):
    """Any AI failure. Messages never contain keys or provider response bodies."""


class AIUnavailable(AIError):
    """Stop calling the provider for now: budget exhausted, rate limited, or no usable key."""


class AIInvalid(AIError):
    """The model answered, but the answer is unusable."""


@dataclass(frozen=True)
class VisionInput:
    ref: str
    jpeg: bytes
    hints: tuple[str, ...] = ()
    prescreen_score: float = 0.0
    ocr_text: str = ""


@dataclass(frozen=True)
class TrendBrief:
    id: str
    title: str
    keywords: tuple[str, ...] = ()


@dataclass(frozen=True)
class CaptionRequest:
    analysis: Analysis
    trends: tuple[TrendBrief, ...] = ()
    general: int = 2
    adult: int = 0
    adult_styles: tuple[str, ...] = ()
    avoid: tuple[str, ...] = ()


@dataclass(frozen=True)
class PackNameRequest:
    category: str
    kind: str
    rating: str
    samples: tuple[str, ...] = ()
    trend: str = ""


@dataclass(frozen=True)
class PackName:
    title: str
    description: str


class AI(Protocol):
    provider: str

    def remaining(self, kind: str) -> int: ...
    def images_per_request(self) -> int: ...
    def analyze(self, items: Sequence[VisionInput], categories: Sequence[str],
                protected: Sequence[str]) -> list[Analysis]: ...
    def write_captions(self, request: CaptionRequest) -> list[Caption]: ...
    def judge_captions(self, analysis: Analysis, captions: Sequence[Caption]) -> list[bool]: ...
    def name_pack(self, request: PackNameRequest) -> PackName: ...
