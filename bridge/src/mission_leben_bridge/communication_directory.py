from __future__ import annotations

import json
import threading
from pathlib import Path
from typing import Any


class CommunicationDirectory:
    def __init__(self, path: Path):
        self.path = path
        self._lock = threading.Lock()
        self._mtime_ns = -1
        self._users: dict[str, str] = {}
        self._calendar_subjects: frozenset[str] = frozenset()

    def talk_subjects(self, nextcloud_user_ids: set[str]) -> tuple[str, ...]:
        users = self._load()
        return tuple(
            sorted(
                {
                    users[user_id]
                    for user_id in nextcloud_user_ids
                    if user_id in users
                }
            )
        )

    def allows_personal_calendar(self, subject: str) -> bool:
        self._load()
        return subject in self._calendar_subjects

    def _load(self) -> dict[str, str]:
        try:
            mtime_ns = self.path.stat().st_mtime_ns
        except OSError as error:
            raise RuntimeError("communication directory is unavailable") from error
        with self._lock:
            if mtime_ns == self._mtime_ns:
                return self._users
            try:
                payload: Any = json.loads(self.path.read_text(encoding="utf-8"))
            except (OSError, json.JSONDecodeError) as error:
                raise RuntimeError("communication directory is invalid") from error
            assignments = payload.get("assignments", []) if isinstance(payload, dict) else None
            if not isinstance(assignments, list):
                raise RuntimeError("communication directory has no assignments")
            users: dict[str, str] = {}
            calendar_subjects: set[str] = set()
            for value in assignments:
                if not isinstance(value, dict):
                    continue
                subject = str(value.get("subject", "")).strip()
                if value.get("personal_calendar") is True and value.get("zimbra") is True:
                    if not subject:
                        raise RuntimeError("calendar assignment has no subject")
                    calendar_subjects.add(subject)
                if not bool(value.get("talk")):
                    continue
                user_id = str(value.get("nextcloud_user_id", "")).strip()
                if not user_id or not subject:
                    raise RuntimeError("Talk assignment has no user id or subject")
                if user_id in users and users[user_id] != subject:
                    raise RuntimeError("Talk user id is assigned more than once")
                users[user_id] = subject
            self._users = users
            self._calendar_subjects = frozenset(calendar_subjects)
            self._mtime_ns = mtime_ns
            return self._users
