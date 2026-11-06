"""Groq client against httpx.MockTransport: no network, no real keys."""
from __future__ import annotations

import json
from typing import Any

import httpx
import pytest
from conftest import make_analysis

from stickerworker.ai import Budget, BudgetExhausted, VisionInput
from stickerworker.ai.groq import API_URL, GroqClient, InvalidResponse, RateLimited, RequestTooLarge, parse_duration
from stickerworker.ai.groq_ai import GroqAI
from stickerworker.ai.schemas import analysis_schema, caption_schema, judge_schema, pack_name_schema
from stickerworker.analysis import Caption
from stickerworker.config import Env
from stickerworker.settings import DEFAULT_ROWS

CATEGORIES = DEFAULT_ROWS["categories"]["enabled"]
KEYS = ("gsk_test_key_zero", "gsk_test_key_one")


class FakeClock:
    def __init__(self) -> None:
        self.t = 1000.0
        self.sleeps: list[float] = []

    def now(self) -> float:
        return self.t

    def sleep(self, seconds: float) -> None:
        self.sleeps.append(round(seconds, 3))
        self.t += seconds


def ok_body(content: dict[str, Any], prompt: int = 120, completion: int = 40, finish: str = "stop") -> dict[str, Any]:
    return {"choices": [{"index": 0, "finish_reason": finish, "message": {"role": "assistant",
                                                                          "content": json.dumps(content)}}],
            "usage": {"prompt_tokens": prompt, "completion_tokens": completion, "total_tokens": prompt + completion}}


def make(handler, keys=KEYS, budget: Budget | None = None, max_wait: float = 30.0):
    ledger: list[dict[str, Any]] = []
    clock = FakeClock()
    requests: list[httpx.Request] = []

    def record(request: httpx.Request) -> httpx.Response:
        requests.append(request)
        return handler(request, len(requests))

    client = GroqClient(keys, budget=budget or Budget({"vision": 60, "text": 400}), ledger=ledger.append,
                        transport=httpx.MockTransport(record), sleep=clock.sleep, clock=clock.now,
                        max_wait_s=max_wait)
    return client, ledger, clock, requests


def chat(client: GroqClient, **overrides: Any):
    params = dict(purpose="caption", model="openai/gpt-oss-120b", kind="text", units=1,
                  messages=[{"role": "system", "content": "hi"}, {"role": "user", "content": "{}"}],
                  schema=caption_schema(), schema_name="sticker_captions")
    params.update(overrides)
    return client.chat_json(**params)


def key_of(request: httpx.Request) -> str:
    return request.headers["authorization"].removeprefix("Bearer ")


# ------------------------------------------------------------------ success + payload shape
def test_success_payload_and_ledger():
    client, ledger, _, requests = make(lambda req, n: httpx.Response(200, json=ok_body({"captions": []})))
    result = chat(client)
    request = requests[0]
    body = json.loads(request.content)
    assert str(request.url) == API_URL
    assert key_of(request) == KEYS[0]
    assert body["response_format"]["type"] == "json_schema"
    assert body["response_format"]["json_schema"]["strict"] is True
    assert body["response_format"]["json_schema"]["name"] == "sticker_captions"
    assert body["max_completion_tokens"] == 1024
    assert result.content == {"captions": []} and result.key_slot == 0
    assert ledger == [{"provider": "groq", "model": "openai/gpt-oss-120b", "key_slot": 0, "purpose": "caption",
                       "ok": True, "status": 200, "latency_ms": 0, "input_tokens": 120, "output_tokens": 40,
                       "error": None}]
    assert client.budget.used["text"] == 1


def test_ledger_and_errors_never_contain_keys():
    def handler(req, n):
        return httpx.Response(429 if n == 1 else 200, headers={"retry-after": "1"},
                              json={"error": {"message": f"bad key {KEYS[0]}", "code": "rate_limit_exceeded"}}
                              if n == 1 else ok_body({"captions": []}))
    client, ledger, _, _ = make(handler)
    chat(client)
    dumped = json.dumps(ledger) + repr(client.slots)
    assert all(key not in dumped for key in KEYS)
    assert ledger[0]["error"] == "http_429:rate_limit_exceeded"


def schema_objects(node: Any):
    if isinstance(node, dict):
        if node.get("type") == "object":
            yield node
        for value in node.values():
            yield from schema_objects(value)
    elif isinstance(node, list):
        for value in node:
            yield from schema_objects(value)


@pytest.mark.parametrize("schema", [analysis_schema(CATEGORIES), caption_schema(), pack_name_schema(),
                                    judge_schema()])
def test_strict_schemas_require_every_property(schema):
    objects = list(schema_objects(schema))
    assert objects
    for obj in objects:
        assert obj["additionalProperties"] is False
        assert obj["required"] == list(obj["properties"])


def test_analysis_schema_enumerates_categories_and_safety_fields():
    item = analysis_schema(CATEGORIES)["properties"]["images"]["items"]["properties"]
    assert item["category"]["enum"] == CATEGORIES
    assert item["nsfw"]["enum"] == ["none", "suggestive", "explicit"]
    for field in ("minors_likely", "real_person", "hate_or_slur", "has_text"):
        assert item[field] == {"type": "boolean"}


# ------------------------------------------------------------------ rate limits
def test_429_cools_the_active_key_and_waits_instead_of_switching_keys():
    def handler(req, n):
        if n == 1:
            return httpx.Response(429, headers={"retry-after": "20"}, json={"error": {"code": "rate_limit_exceeded"}})
        return httpx.Response(200, json=ok_body({"captions": []}))
    client, ledger, clock, requests = make(handler)
    result = chat(client)
    assert result.key_slot == 0
    assert [key_of(r) for r in requests] == [KEYS[0], KEYS[0]]
    assert [(row["key_slot"], row["status"], row["ok"]) for row in ledger] == [(0, 429, False), (0, 200, True)]
    assert clock.sleeps == [20.0]


def test_backup_keys_are_never_used_to_get_around_a_rate_limit():
    client, _, _, requests = make(lambda req, n: httpx.Response(429, headers={"retry-after": "3600"}))
    with pytest.raises(RateLimited):
        chat(client)
    with pytest.raises(RateLimited):  # still cooling: no second request at all
        chat(client)
    assert [key_of(r) for r in requests] == [KEYS[0]]


def test_requests_are_spaced_by_the_minimum_interval():
    client, _, clock, requests = make(lambda req, n: httpx.Response(200, json=ok_body({"captions": []})))
    client.min_interval_s = 2.5
    for _ in range(3):
        chat(client)
    assert len(requests) == 3 and clock.sleeps == [2.5, 2.5]


def test_single_key_waits_for_short_retry_after():
    def handler(req, n):
        if n == 1:
            return httpx.Response(429, headers={"retry-after": "5"})
        return httpx.Response(200, json=ok_body({"captions": []}))
    client, _, clock, _ = make(handler, keys=KEYS[:1])
    chat(client)
    assert clock.sleeps == [5.0]


def test_long_cooldown_raises_rate_limited_and_refunds_budget():
    client, ledger, _, requests = make(lambda req, n: httpx.Response(429, headers={"retry-after": "3600"}))
    with pytest.raises(RateLimited) as info:
        chat(client)
    assert info.value.retry_in == pytest.approx(3600)
    assert len(requests) == 1  # the active key only
    assert client.budget.used.get("text", 0) == 0


def test_daily_request_quota_header_cools_key():
    headers = {"x-ratelimit-remaining-requests": "0", "x-ratelimit-reset-requests": "2m30s"}
    client, _, clock, requests = make(lambda req, n: httpx.Response(200, headers=headers,
                                                                    json=ok_body({"captions": []})),
                                      keys=KEYS[:1])
    chat(client)
    assert client.slots[0].cooldown_until == pytest.approx(clock.t + 150)
    with pytest.raises(RateLimited):  # 150 s > max_wait 30 s
        chat(client)
    assert len(requests) == 1


def test_token_pacing_waits_for_tpm_reset():
    headers = {"x-ratelimit-remaining-tokens": "500", "x-ratelimit-reset-tokens": "7.5s"}
    client, _, clock, requests = make(lambda req, n: httpx.Response(200, headers=headers,
                                                                    json=ok_body({"captions": []})),
                                      keys=KEYS[:1])
    chat(client)
    chat(client, max_tokens=4000)  # estimate exceeds the 500 remaining tokens
    assert clock.sleeps == [7.5]
    assert len(requests) == 2


def test_parse_duration_formats():
    assert parse_duration("7.66s") == pytest.approx(7.66)
    assert parse_duration("2m59.56s") == pytest.approx(179.56)
    assert parse_duration("1h2m") == pytest.approx(3720)
    assert parse_duration("120ms") == pytest.approx(0.12)
    assert parse_duration("30") == 30
    assert parse_duration(None) is None and parse_duration("soon") is None


# ------------------------------------------------------------------ budget + other failures
def test_budget_exhaustion_blocks_before_any_request():
    client, ledger, _, requests = make(lambda req, n: httpx.Response(200, json=ok_body({})),
                                       budget=Budget({"vision": 2, "text": 0}))
    with pytest.raises(BudgetExhausted):
        chat(client, kind="vision", units=3)
    with pytest.raises(BudgetExhausted):
        chat(client)
    assert requests == [] and ledger == []


def test_request_too_large_refunds_budget():
    client, _, _, _ = make(lambda req, n: httpx.Response(413, json={"error": {"code": "request_too_large"}}))
    with pytest.raises(RequestTooLarge):
        chat(client, kind="vision", units=3)
    assert client.budget.remaining("vision") == 60


def test_truncated_output_is_invalid_and_still_counts():
    client, ledger, _, _ = make(lambda req, n: httpx.Response(200, json=ok_body({"a": 1}, finish="length")))
    with pytest.raises(InvalidResponse):
        chat(client)
    assert client.budget.used["text"] == 1
    assert ledger[0]["ok"] is False and ledger[0]["error"] == "finish_length"


def test_invalid_key_is_disabled_and_next_key_used():
    def handler(req, n):
        if key_of(req) == KEYS[0]:
            return httpx.Response(401, json={"error": {"code": "invalid_api_key"}})
        return httpx.Response(200, json=ok_body({"captions": []}))
    client, _, _, _ = make(handler)
    assert chat(client).key_slot == 1
    assert client.slots[0].disabled


def test_server_errors_retry_then_succeed():
    client, _, clock, requests = make(lambda req, n: httpx.Response(503) if n < 3 else
                                      httpx.Response(200, json=ok_body({"captions": []})), keys=KEYS[:1])
    chat(client)
    assert len(requests) == 3 and clock.sleeps == [2.0, 4.0]


# ------------------------------------------------------------------ GroqAI behaviour
def vision_item(index: int, **overrides: Any) -> dict[str, Any]:
    item = {"image": index, "kind": "reaction", "has_text": False, "text": "", "language": "none",
            "category": "reactions", "mood": ["shocked"], "tags": ["shocked", "hairan", "omg", "wow", "reaction"],
            "emojis": ["😱"], "description": "surprised face", "nsfw": "none", "minors_likely": False,
            "real_person": True, "public_figure": "", "violence_or_gore": "none", "hate_or_slur": False,
            "sticker_score": 8, "best_recipe": "caption", "crop": {"x": 0.1, "y": 0.1, "w": 0.8, "h": 0.8},
            "caption_ideas": ["Kya baat hai", "Sach mein?", "Arre waah"]}
    return {**item, **overrides}


def test_vision_strict_schema_rejected_falls_back_to_json_object():
    def handler(req, n):
        body = json.loads(req.content)
        if body["response_format"]["type"] == "json_schema":
            return httpx.Response(400, json={"error": {"code": "invalid_request_error"}})
        assert "JSON schema" in body["messages"][0]["content"]
        assert body["messages"][1]["content"][1]["image_url"]["url"].startswith("data:image/jpeg;base64,")
        return httpx.Response(200, json=ok_body({"images": [vision_item(1), vision_item(0, kind="meme")]}))
    client, ledger, _, requests = make(handler)
    ai = GroqAI(client, Env())
    items = [VisionInput("a", b"\xff\xd8\xffjpeg-a"), VisionInput("b", b"\xff\xd8\xffjpeg-b")]
    analyses = ai.analyze(items, CATEGORIES, ["Amitabh Bachchan"])
    assert [a.kind for a in analyses] == ["meme", "reaction"]  # mapped back by "image" index
    assert [json.loads(r.content)["response_format"]["type"] for r in requests] == ["json_schema", "json_object"]
    assert client.budget.used["vision"] == 2  # the rejected attempt was refunded


def test_images_per_request_respects_tpm():
    client, _, _, _ = make(lambda req, n: httpx.Response(200))
    assert GroqAI(client, Env(vision_tpm=8000)).images_per_request() == 2
    assert GroqAI(client, Env(vision_tpm=30000)).images_per_request() == 3
    assert GroqAI(client, Env(vision_tpm=4000)).images_per_request() == 1


def test_judge_fails_closed_on_missing_verdicts():
    client, _, _, _ = make(lambda req, n: httpx.Response(200, json=ok_body(
        {"verdicts": [{"index": 0, "allowed": True, "reason": "ok"}]})))
    ai = GroqAI(client, Env())
    assert ai.judge_captions(make_analysis(), [Caption("one"), Caption("two")]) == [True, False]
    assert GroqAI(client, Env(safety_model="")).judge_captions(make_analysis(), [Caption("x")]) == [True]
