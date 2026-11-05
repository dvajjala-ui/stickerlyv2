"""Quality gate between `create` and Discover.

Every finished sticker gets a local 0-100 score (imaging/quality.py), including a near-duplicate check
against existing stickers' visible-area pHash. A sticker the policy would publish but that scores below
settings.quality_gate.min_score is stored as `held`: never public, never packed, waiting for an admin.
Drafts and rejects are left as the policy decided; their score is still recorded.

The optional vision judge (settings.quality_gate.vision_judge, off by default) only sees stickers whose
local score is within `judge_band` of the threshold and that have no hard problem. Each call is one image
of budgets.vision_per_day. When the budget is spent, or the provider fails, the judge stops for the rest of
the run and the local score decides.
"""
from __future__ import annotations

import hashlib
from collections import Counter
from collections.abc import Sequence
from dataclasses import replace
from typing import Any

from PIL import Image

from ..ai.base import AIError, AIUnavailable, QualityInput
from ..ai.budget import BudgetExhausted
from ..imaging.decode import vision_jpeg
from ..imaging.quality import Box4, QualityReport, decode_frames, render_phash, score_image, with_judge
from .context import Context
from .fingerprints import RENDER_NEAR_DUPLICATE, nearest

FINGERPRINT_WINDOW = 5000
JUDGE_BACKGROUND = (128, 128, 128)  # mid-grey shows both the white outline and the black caption stroke
DUPLICATE_STATUSES = ("published", "draft")  # held or rejected stickers never block a better copy


class QualityGate:
    def __init__(self, ctx: Context, known: Sequence[tuple[str, str, str | None]] | None = None) -> None:
        self.ctx = ctx
        self.settings = ctx.settings.quality
        rows = known if known is not None else ctx.store.render_fingerprints(FINGERPRINT_WINDOW)
        self.known: list[tuple[str, str, str | None]] = list(rows)  # (sticker id, render pHash, source id)
        self.counts: Counter[str] = Counter()
        self.judge_stopped = ""

    # ------------------------------------------------------------ scoring
    def check(self, data: bytes, *, kind: str, source_id: str | None, caption_box: Box4 | None = None,
              caption: str = "", hints: Sequence[str] = (), wants_publish: bool = True) -> QualityReport:
        """Score one validated sticker. The judge is only asked when the sticker would otherwise publish."""
        try:
            image, durations = decode_frames(data)
            phash = render_phash(image)
            others = [(sid, ph) for sid, ph, src in self.known if source_id is None or src != source_id]
            duplicate = nearest(phash, others, limit=RENDER_NEAR_DUPLICATE)
            report = score_image(image, caption_box=caption_box, durations=durations, duplicate=duplicate,
                                 phash=phash)
        except (OSError, ValueError, ArithmeticError) as error:
            self.ctx.log(f"quality: check failed ({type(error).__name__}); the sticker is held")
            return QualityReport(0, ("quality_check_failed",), {"error": type(error).__name__}, 0, 0)
        if wants_publish and self._judge_wanted(report):
            report = self._judge(report, image, data, kind=kind, caption=caption, hints=hints)
        return report

    def _judge_wanted(self, report: QualityReport) -> bool:
        gate = self.settings
        return (gate.enabled and gate.vision_judge and not report.capped
                and gate.min_score - gate.judge_band <= report.score < gate.min_score + gate.judge_band)

    def _skip(self, report: QualityReport, why: str) -> QualityReport:
        self.counts["judge_skipped"] += 1
        return replace(report, signals={**report.signals, "judge": {"skipped": why}})

    def _judge(self, report: QualityReport, image: Image.Image, data: bytes, *, kind: str, caption: str,
               hints: Sequence[str]) -> QualityReport:
        ai = self.ctx.ai
        if not self.judge_stopped:
            if ai is None or not callable(getattr(ai, "judge_quality", None)):
                self.judge_stopped = "no_ai_provider"
            elif ai.remaining("vision") <= 0:
                self.judge_stopped = "vision_budget_exhausted"
        if self.judge_stopped or ai is None:
            return self._skip(report, self.judge_stopped)
        item = QualityInput(hashlib.sha256(data).hexdigest()[:16],
                            vision_jpeg(image, max_side=512, quality=85, background=JUDGE_BACKGROUND),
                            kind, caption, tuple(hints))
        try:
            verdict = ai.judge_quality(item)
        except AIError as error:
            self.judge_stopped = ("vision_budget_exhausted" if isinstance(error, BudgetExhausted)
                                  else "ai_unavailable" if isinstance(error, AIUnavailable) else "judge_error")
            self.ctx.log(f"quality: vision judge stopped for this run ({error}); local scores decide")
            return self._skip(report, self.judge_stopped)
        self.counts["judged"] += 1
        return with_judge(report, verdict.score, verdict.issues)

    # ------------------------------------------------------------ decisions
    def status(self, policy_status: str, report: QualityReport) -> str:
        """`published` becomes `held` below the threshold; `draft` stays a draft (a human reviews it anyway)."""
        if policy_status == "published" and self.settings.enabled and report.score < self.settings.min_score:
            return "held"
        return policy_status

    def note(self, report: QualityReport) -> str:
        """review_note for a held sticker, readable in the admin before it shows the quality columns."""
        return f"quality_gate:{report.score}<{self.settings.min_score}:" + "+".join(report.reasons or ("low_score",))

    def remember(self, sticker_id: str, report: QualityReport, source_id: str | None, status: str) -> None:
        """Stickers made in this run count as existing for the near-duplicate check."""
        if status in DUPLICATE_STATUSES and report.render_phash:
            self.known.insert(0, (sticker_id, report.render_phash, source_id))

    def summary(self) -> dict[str, Any]:
        out: dict[str, Any] = {f"quality_{key}": value for key, value in self.counts.items() if value}
        if self.judge_stopped:
            out["quality_judge_stopped"] = self.judge_stopped
        return out
