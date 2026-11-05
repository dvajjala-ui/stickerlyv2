"""UTC/IST helpers. IST has no DST, so a fixed offset avoids needing tzdata on Windows."""
from __future__ import annotations

from datetime import UTC, date, datetime, timedelta, timezone

IST = timezone(timedelta(hours=5, minutes=30), "IST")


def utcnow() -> datetime:
    return datetime.now(UTC)


def iso(moment: datetime) -> str:
    """Fixed-width UTC ISO-8601 string (sorts lexicographically, safe in URLs)."""
    if moment.tzinfo is None:
        moment = moment.replace(tzinfo=UTC)
    return moment.astimezone(UTC).strftime("%Y-%m-%dT%H:%M:%S.%fZ")


def parse_iso(value: str | None) -> datetime | None:
    if not value:
        return None
    try:
        moment = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        return None
    return moment if moment.tzinfo else moment.replace(tzinfo=UTC)


def ist_day(moment: datetime) -> date:
    return moment.astimezone(IST).date()


def ist_day_start(moment: datetime) -> datetime:
    """Midnight IST of the moment's IST day, as an aware UTC datetime."""
    day = ist_day(moment)
    return datetime(day.year, day.month, day.day, tzinfo=IST).astimezone(UTC)
