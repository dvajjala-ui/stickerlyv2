"""Groq chat-completions client (OpenAI-compatible) with key failover, pacing and a call ledger.

- Several keys (GROQ_API_KEYS) are tried in order; a key that gets HTTP 429 cools
  down for `retry-after` (or the x-ratelimit reset) and the next key is used. Keys
  are meant for failover or separate paid orgs, never to multiply free quota.
- x-ratelimit-remaining-tokens/-requests are honoured before sending: the client
  waits (up to max_wait_s) or raises RateLimited so the stage can stop cleanly.
- Every HTTP attempt is logged through `ledger` with the key's slot index only.
- Budget units are taken before sending and refunded when nothing was consumed.
"""
from __future__ import annotations

import contextlib
import json
import re
import time
from collections.abc import Callable, Sequence
from dataclasses import dataclass, field
from email.utils import parsedate_to_datetime
from typing import Any

import httpx

from .base import AIError, AIInvalid, AIUnavailable
from .budget import Budget

API_URL = "https://api.groq.com/openai/v1/chat/completions"
IMAGE_TOKENS = 2048
_CODE = re.compile(r"[a-z][a-z0-9_]{0,63}")
_DURATION = re.compile(r"(\d+(?:\.\d+)?)(ms|h|m|s)")
_UNITS = {"ms": 0.001, "s": 1.0, "m": 60.0, "h": 3600.0}


class GroqError(AIError):
    def __init__(self, code: str, status: int | None = None) -> None:
        self.code = code
        self.status = status
        super().__init__(f"groq {code}" + (f" (HTTP {status})" if status else ""))


class RateLimited(GroqError, AIUnavailable):
    def __init__(self, retry_in: float) -> None:
        self.retry_in = retry_in
        super().__init__("rate_limited", 429)


class NoUsableKeys(GroqError, AIUnavailable):
    pass


class RequestTooLarge(GroqError):
    pass


class SchemaRejected(GroqError):
    pass


class InvalidResponse(GroqError, AIInvalid):
    pass


@dataclass
class KeySlot:
    index: int
    key: str = field(repr=False)
    cooldown_until: float = 0.0
    disabled: bool = False
    tokens_remaining: int | None = None
    tokens_reset_at: float = 0.0


@dataclass(frozen=True)
class ChatResult:
    content: dict[str, Any]
    model: str
    key_slot: int
    input_tokens: int | None
    output_tokens: int | None


def parse_duration(value: str | None) -> float | None:
    """'7.66s', '2m59.56s', '1h2m', '120ms' or plain seconds -> seconds."""
    if not value:
        return None
    text = value.strip()
    try:
        return max(0.0, float(text))
    except ValueError:
        pass
    parts = _DURATION.findall(text)
    return sum(float(amount) * _UNITS[unit] for amount, unit in parts) if parts else None


def retry_after(headers: httpx.Headers, now_epoch: float) -> float | None:
    value = headers.get("retry-after")
    seconds = parse_duration(value)
    if seconds is None and value:
        try:
            seconds = max(0.0, parsedate_to_datetime(value).timestamp() - now_epoch)
        except (TypeError, ValueError):
            seconds = None
    return seconds


def safe_error_code(response: httpx.Response) -> str:
    """Provider error identifier only (never the free-text message)."""
    try:
        body = response.json()
    except ValueError:
        return ""
    error = body.get("error") if isinstance(body, dict) else None
    if isinstance(error, dict):
        for key in ("code", "type"):
            value = error.get(key)
            if isinstance(value, str) and _CODE.fullmatch(value):
                return value
    return ""


def parse_json_content(text: Any) -> dict[str, Any]:
    if not isinstance(text, str):
        raise ValueError("no content")
    cleaned = re.sub(r"<think>.*?</think>", "", text, flags=re.S).strip()
    if cleaned.startswith("```"):
        cleaned = re.sub(r"^```[a-zA-Z]*\s*|\s*```$", "", cleaned).strip()
    parsed = json.loads(cleaned)
    if not isinstance(parsed, dict):
        raise ValueError("content is not an object")
    return parsed


class GroqClient:
    def __init__(self, keys: Sequence[str], *, budget: Budget, ledger: Callable[[dict[str, Any]], None],
                 transport: httpx.BaseTransport | None = None, sleep: Callable[[float], None] = time.sleep,
                 clock: Callable[[], float] = time.monotonic, max_wait_s: float = 75.0,
                 timeout_s: float = 120.0) -> None:
        if not keys:
            raise NoUsableKeys("no_keys")
        self.slots = [KeySlot(index, key) for index, key in enumerate(keys)]
        self.budget = budget
        self.max_wait_s = max_wait_s
        self._ledger = ledger
        self._sleep = sleep
        self._clock = clock
        self._http = httpx.Client(timeout=timeout_s, transport=transport,
                                  headers={"Content-Type": "application/json", "User-Agent": "stickerworker/0.1"})

    def close(self) -> None:
        self._http.close()

    # ------------------------------------------------------------ public
    def chat_json(self, *, purpose: str, model: str, messages: list[dict[str, Any]], kind: str, units: int = 1,
                  schema: dict[str, Any] | None = None, schema_name: str = "result", json_mode: bool = True,
                  max_tokens: int = 1024, temperature: float = 0.2,
                  extra: dict[str, Any] | None = None) -> ChatResult:
        payload: dict[str, Any] = {"model": model, "messages": messages, "temperature": temperature,
                                   "max_completion_tokens": max_tokens}
        if schema is not None:
            payload["response_format"] = {"type": "json_schema", "json_schema": {
                "name": schema_name, "strict": True, "schema": schema}}
        elif json_mode:
            payload["response_format"] = {"type": "json_object"}
        payload.update(extra or {})
        self.budget.take(kind, units)
        consumed = False
        try:
            result = self._send(payload, purpose, self.estimate(messages, units if kind == "vision" else 0,
                                                                max_tokens))
            consumed = True
            return result
        except InvalidResponse:
            consumed = True  # HTTP 200: the provider counted these tokens
            raise
        finally:
            if not consumed:
                self.budget.refund(kind, units)

    @staticmethod
    def estimate(messages: list[dict[str, Any]], images: int, max_tokens: int) -> int:
        chars = 0
        for message in messages:
            content = message.get("content")
            if isinstance(content, str):
                chars += len(content)
            elif isinstance(content, list):
                chars += sum(len(part.get("text", "")) for part in content if isinstance(part, dict))
        return int(chars / 3.5) + images * IMAGE_TOKENS + max_tokens

    # ------------------------------------------------------------ internals
    def _wait_for(self, slot: KeySlot, estimate: int, now: float) -> float:
        wait = max(0.0, slot.cooldown_until - now)
        if slot.tokens_remaining is not None and slot.tokens_remaining < estimate and slot.tokens_reset_at > now:
            wait = max(wait, slot.tokens_reset_at - now)
        return wait

    def _pick(self, estimate: int) -> KeySlot:
        while True:
            usable = [slot for slot in self.slots if not slot.disabled]
            if not usable:
                raise NoUsableKeys("no_usable_keys")
            now = self._clock()
            waits = [(self._wait_for(slot, estimate, now), slot) for slot in usable]
            ready = [slot for wait, slot in waits if wait <= 0]
            if ready:
                return ready[0]
            wait = min(w for w, _ in waits)
            if wait > self.max_wait_s:
                raise RateLimited(wait)
            self._sleep(wait)

    def _absorb(self, slot: KeySlot, headers: httpx.Headers) -> None:
        now = self._clock()
        tokens = headers.get("x-ratelimit-remaining-tokens")
        if tokens and tokens.strip().isdigit():
            slot.tokens_remaining = int(tokens)
            slot.tokens_reset_at = now + (parse_duration(headers.get("x-ratelimit-reset-tokens")) or 60.0)
        if (headers.get("x-ratelimit-remaining-requests") or "").strip() == "0":
            reset = parse_duration(headers.get("x-ratelimit-reset-requests")) or 60.0
            slot.cooldown_until = max(slot.cooldown_until, now + reset)

    def _log(self, slot: KeySlot, payload: dict[str, Any], purpose: str, started: float, *, ok: bool,
             status: int | None, error: str | None = None, usage: dict[str, Any] | None = None) -> None:
        usage = usage or {}
        row = {"provider": "groq", "model": payload["model"], "key_slot": slot.index, "purpose": purpose, "ok": ok,
               "status": status, "latency_ms": int((self._clock() - started) * 1000),
               "input_tokens": usage.get("prompt_tokens") if isinstance(usage.get("prompt_tokens"), int) else None,
               "output_tokens": usage.get("completion_tokens")
               if isinstance(usage.get("completion_tokens"), int) else None,
               "error": error}
        with contextlib.suppress(Exception):  # the ledger must never break a call
            self._ledger(row)

    def _send(self, payload: dict[str, Any], purpose: str, estimate: int) -> ChatResult:
        transient = 0
        while True:
            slot = self._pick(estimate)
            started = self._clock()
            try:
                response = self._http.post(API_URL, json=payload, headers={"Authorization": f"Bearer {slot.key}"})
            except httpx.TransportError as error:
                self._log(slot, payload, purpose, started, ok=False, status=None,
                          error=f"transport:{type(error).__name__}")
                transient += 1
                if transient >= 3:
                    raise GroqError("transport") from None
                self._sleep(2.0 * transient)
                continue
            self._absorb(slot, response.headers)
            status = response.status_code
            if status == 200:
                return self._parse(slot, payload, purpose, started, response)
            code = safe_error_code(response)
            self._log(slot, payload, purpose, started, ok=False, status=status,
                      error=f"http_{status}" + (f":{code}" if code else ""))
            if status == 429:
                wait = (retry_after(response.headers, time.time())
                        or parse_duration(response.headers.get("x-ratelimit-reset-tokens"))
                        or parse_duration(response.headers.get("x-ratelimit-reset-requests")) or 60.0)
                slot.cooldown_until = max(slot.cooldown_until, self._clock() + wait)
                continue
            if status in (401, 403):
                slot.disabled = True
                continue
            if status == 413:
                raise RequestTooLarge(code or "request_too_large", status)
            if status == 400 and code == "json_validate_failed":
                raise InvalidResponse(code, status)
            if status == 400 and payload.get("response_format", {}).get("type") == "json_schema":
                raise SchemaRejected(code or "bad_request", status)
            if status >= 500 or status == 408:
                transient += 1
                if transient >= 3:
                    raise GroqError(code or "server_error", status)
                self._sleep(2.0 * transient)
                continue
            raise GroqError(code or "bad_request", status)

    def _parse(self, slot: KeySlot, payload: dict[str, Any], purpose: str, started: float,
               response: httpx.Response) -> ChatResult:
        try:
            body = response.json()
        except ValueError:
            self._log(slot, payload, purpose, started, ok=False, status=200, error="invalid_envelope")
            raise InvalidResponse("invalid_envelope", 200) from None
        usage = body.get("usage") if isinstance(body, dict) and isinstance(body.get("usage"), dict) else {}
        try:
            choice = body["choices"][0]
            reason = choice.get("finish_reason") or "stop"
            if reason != "stop":
                raise InvalidResponse(f"finish_{reason}"[:40], 200)
            if choice["message"].get("refusal"):
                raise InvalidResponse("refused", 200)
            content = parse_json_content(choice["message"].get("content"))
        except InvalidResponse as error:
            self._log(slot, payload, purpose, started, ok=False, status=200, error=error.code, usage=usage)
            raise
        except (KeyError, IndexError, TypeError, ValueError):
            self._log(slot, payload, purpose, started, ok=False, status=200, error="invalid_content", usage=usage)
            raise InvalidResponse("invalid_content", 200) from None
        self._log(slot, payload, purpose, started, ok=True, status=200, usage=usage)
        return ChatResult(content, payload["model"], slot.index,
                          usage.get("prompt_tokens") if isinstance(usage.get("prompt_tokens"), int) else None,
                          usage.get("completion_tokens") if isinstance(usage.get("completion_tokens"), int) else None)
