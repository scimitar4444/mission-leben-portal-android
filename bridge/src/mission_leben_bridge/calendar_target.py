"""Bounded calendar identity, never a caller-controlled URL."""
import re
from datetime import datetime

_TARGET = re.compile(r"([0-9]{1,20}-[0-9]{1,20})\|([0-9]{8}(?:T[0-9]{6}Z)?|)\|([0-9]{13})\|([0-9]{13})")


def valid_calendar_target(value: str) -> bool:
    match = _TARGET.fullmatch(value)
    if not match:
        return False
    _, recurrence, start, end = match.groups()
    if int(end) <= int(start):
        return False
    if recurrence:
        try:
            datetime.strptime(recurrence, "%Y%m%d" if len(recurrence) == 8 else "%Y%m%dT%H%M%SZ")
        except ValueError:
            return False
    return True


def calendar_target(invite_id: str, recurrence_id: str, start: int, end: int) -> str:
    value = f"{invite_id}|{recurrence_id}|{start}|{end}"
    return value if valid_calendar_target(value) else ""
