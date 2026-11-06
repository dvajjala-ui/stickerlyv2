"""UTC/IST helpers — these drive the daily AI-budget reset and daily-pack boundaries, so correctness matters."""
from __future__ import annotations

from datetime import UTC, datetime, timedelta, timezone

from stickerworker.timeutil import IST, ist_day, ist_day_start, iso, parse_iso


def test_iso_is_fixed_width_utc_for_naive_and_aware():
    aware = datetime(2026, 11, 7, 3, 30, 0, 123456, tzinfo=UTC)
    assert iso(aware) == "2026-11-07T03:30:00.123456Z"
    # a naive datetime is treated as UTC, not local time
    assert iso(datetime(2026, 11, 7, 3, 30, 0, 123456)) == "2026-11-07T03:30:00.123456Z"
    # a non-UTC offset is converted to UTC
    ist_moment = datetime(2026, 11, 7, 9, 0, 0, tzinfo=IST)
    assert iso(ist_moment) == "2026-11-07T03:30:00.000000Z"


def test_parse_iso_handles_z_offset_naive_none_and_garbage():
    assert parse_iso(None) is None
    assert parse_iso("") is None
    assert parse_iso("not a date") is None
    assert parse_iso("2026-11-07T03:30:00Z") == datetime(2026, 11, 7, 3, 30, tzinfo=UTC)
    assert parse_iso("2026-11-07T09:00:00+05:30").astimezone(UTC) == datetime(2026, 11, 7, 3, 30, tzinfo=UTC)
    # a naive value is assumed UTC
    assert parse_iso("2026-11-07T03:30:00").tzinfo is not None
    assert parse_iso("2026-11-07T03:30:00").astimezone(UTC) == datetime(2026, 11, 7, 3, 30, tzinfo=UTC)


def test_iso_parse_iso_round_trip():
    moment = datetime(2026, 1, 2, 23, 59, 59, 7, tzinfo=UTC)
    assert parse_iso(iso(moment)) == moment


def test_ist_day_and_day_start_cross_the_ist_midnight_boundary():
    # 18:45 UTC on 6 Nov is 00:15 IST on 7 Nov (IST = UTC+5:30), so the IST day is the 7th.
    late = datetime(2026, 11, 6, 18, 45, tzinfo=UTC)
    assert ist_day(late).isoformat() == "2026-11-07"
    # Just before: 18:15 UTC is 23:45 IST on the 6th.
    early = datetime(2026, 11, 6, 18, 15, tzinfo=UTC)
    assert ist_day(early).isoformat() == "2026-11-06"
    # ist_day_start is midnight IST of that day, expressed in UTC (18:30 the previous UTC day).
    start = ist_day_start(late)
    assert start == datetime(2026, 11, 6, 18, 30, tzinfo=UTC)
    assert start.astimezone(IST).strftime("%H:%M") == "00:00"
    # Everything from that start until the next IST midnight shares one ist_day.
    assert ist_day(start) == ist_day(start + timedelta(hours=23, minutes=59))
    assert ist_day(start - timedelta(minutes=1)) != ist_day(start)
