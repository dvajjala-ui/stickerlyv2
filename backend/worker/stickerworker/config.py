"""Process configuration from environment variables. Secrets never appear in repr()."""
from __future__ import annotations

import os
import re
from collections.abc import Mapping
from dataclasses import dataclass, field
from pathlib import Path

PACKAGE_DIR = Path(__file__).resolve().parent


def default_out_dir() -> Path:
    """`backend/worker/out` for a source checkout, else `./stickerworker-out`."""
    checkout = PACKAGE_DIR.parent
    if (checkout / "pyproject.toml").is_file():
        return checkout / "out"
    return Path.cwd() / "stickerworker-out"


def parse_keys(*values: str | None) -> tuple[str, ...]:
    """Comma/whitespace separated key list, de-duplicated, order preserved."""
    keys: list[str] = []
    for value in values:
        for key in re.split(r"[\s,;]+", value or ""):
            if key and key not in keys:
                keys.append(key)
    return tuple(keys)


def _int(value: str | None, default: int, low: int, high: int) -> int:
    try:
        return max(low, min(high, int(str(value).strip())))
    except (TypeError, ValueError):
        return default


def _float(value: str | None, default: float, low: float, high: float) -> float:
    try:
        return max(low, min(high, float(str(value).strip())))
    except (TypeError, ValueError):
        return default


@dataclass(frozen=True)
class Env:
    supabase_url: str = ""
    supabase_key: str = field(default="", repr=False)
    groq_keys: tuple[str, ...] = field(default=(), repr=False)
    vision_model: str = "qwen/qwen3.8-27b"
    text_model: str = "openai/gpt-oss-120b"
    small_text_model: str = "openai/gpt-oss-20b"
    safety_model: str = "openai/gpt-oss-safeguard-20b"
    vision_batch: int = 3
    vision_tpm: int = 8000
    vision_reasoning: str = "none"
    text_reasoning: str = "low"
    max_wait_s: float = 75.0
    rembg_model: str = "isnet-general-use"
    out_dir: Path = field(default_factory=default_out_dir)

    @property
    def has_supabase(self) -> bool:
        return bool(self.supabase_url and self.supabase_key)

    @property
    def has_groq(self) -> bool:
        return bool(self.groq_keys)

    @classmethod
    def from_environ(cls, environ: Mapping[str, str] | None = None) -> Env:
        env = os.environ if environ is None else environ

        def text(name: str, default: str) -> str:
            value = env.get(name)
            return value.strip() if value is not None else default

        out = text("STICKERWORKER_OUT", "")
        return cls(
            supabase_url=text("SUPABASE_URL", "").rstrip("/"),
            # New Supabase keys (sb_secret_...) or the legacy service_role JWT.
            supabase_key=text("SUPABASE_SECRET_KEY", "") or text("SUPABASE_SERVICE_ROLE_KEY", ""),
            groq_keys=parse_keys(env.get("GROQ_API_KEYS"), env.get("GROQ_API_KEY")),
            vision_model=text("GROQ_VISION_MODEL", cls.vision_model) or cls.vision_model,
            text_model=text("GROQ_TEXT_MODEL", cls.text_model) or cls.text_model,
            small_text_model=text("GROQ_SMALL_TEXT_MODEL", cls.small_text_model) or cls.small_text_model,
            # An explicitly empty GROQ_SAFETY_MODEL disables the caption judge.
            safety_model=text("GROQ_SAFETY_MODEL", cls.safety_model),
            vision_batch=_int(env.get("GROQ_VISION_BATCH"), 3, 1, 3),
            vision_tpm=_int(env.get("GROQ_VISION_TPM"), 8000, 2500, 10_000_000),
            vision_reasoning=text("GROQ_VISION_REASONING", cls.vision_reasoning),
            text_reasoning=text("GROQ_TEXT_REASONING", cls.text_reasoning),
            max_wait_s=_float(env.get("GROQ_MAX_WAIT_SECONDS"), 75.0, 0.0, 600.0),
            rembg_model=text("REMBG_MODEL", cls.rembg_model) or cls.rembg_model,
            out_dir=Path(out) if out else default_out_dir(),
        )
