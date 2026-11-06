from __future__ import annotations

import dataclasses
import sys
from pathlib import Path
from typing import Any

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent))

from stickerworker.ai import FakeAI  # noqa: E402
from stickerworker.analysis import Analysis  # noqa: E402
from stickerworker.config import Env  # noqa: E402
from stickerworker.pipeline.context import Context  # noqa: E402
from stickerworker.settings import PublishPolicy, parse_settings  # noqa: E402
from stickerworker.store import LocalStore  # noqa: E402

SECRET_VARS = ("GROQ_API_KEYS", "GROQ_API_KEY", "SUPABASE_URL", "SUPABASE_SERVICE_ROLE_KEY", "SUPABASE_SECRET_KEY")


@pytest.fixture(autouse=True)
def no_secrets(monkeypatch: pytest.MonkeyPatch) -> None:
    """Tests never touch the network or real credentials."""
    for name in SECRET_VARS:
        monkeypatch.delenv(name, raising=False)
    monkeypatch.setenv("STICKERWORKER_NO_DOTENV", "1")  # a developer's real .env must never reach tests


@pytest.fixture
def store(tmp_path: Path) -> LocalStore:
    return LocalStore(tmp_path / "out")


@pytest.fixture
def ctx(store: LocalStore, tmp_path: Path) -> Context:
    return Context(store=store, settings=parse_settings(store.load_settings()), env=Env(out_dir=tmp_path / "out"),
                   ai=FakeAI(store.log_ai_call), log=lambda message: None)


def make_analysis(**overrides: Any) -> Analysis:
    base = Analysis(kind="reaction", category="reactions", description="a person looking surprised",
                    tags=("shocked", "hairan", "reaction", "omg", "wow"), mood=("shocked",), sticker_score=8)
    return dataclasses.replace(base, **overrides)


def policy(**overrides: Any) -> PublishPolicy:
    return dataclasses.replace(PublishPolicy(), **overrides)
