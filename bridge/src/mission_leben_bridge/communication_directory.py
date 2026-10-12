from __future__ import annotations

import json
import threading
import time
from pathlib import Path
from typing import Any


class CommunicationDirectory:
    def __init__(self, path: Path):
        self.path = path
        self._lock = threading.Lock()
        self._mtime_ns = -1
        self._users: dict[str, str] = {}
        self._calendar_subjects: frozenset[str] = frozenset()
        self._helpdesk_users: dict[str, str] = {}
        self._generated_at = 0

    def helpdesk_subject(self, login: str) -> str | None:
        self._load()
        # Only the new helpdesk decision requires a recent, policy-evaluated
        # snapshot. A failed identity export must not retain ticket access.
        if not 0 <= time.time() - self._generated_at <= 900:
            raise RuntimeError("helpdesk assignments are stale")
        return self._helpdesk_users.get(login.strip().lower())

    def allows_helpdesk(self, subject: str) -> bool:
        self._load()
        if not 0 <= time.time() - self._generated_at <= 900:
            raise RuntimeError("helpdesk assignments are stale")
        return subject in self._helpdesk_users.values()

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
            helpdesk_users: dict[str, str] = {}
            for value in assignments:
                if not isinstance(value, dict):
                    continue
                subject = str(value.get("subject", "")).strip()
                if value.get("helpdesk") is True:
                    login = str(value.get("helpdesk_login", "")).strip().lower()
                    if not login or not subject or (login in helpdesk_users and helpdesk_users[login] != subject):
                        raise RuntimeError("helpdesk assignment is missing or ambiguous")
                    helpdesk_users[login] = subject
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
            try:
                generated_at = int(payload.get("generated_at", 0))
            except (TypeError, ValueError) as error:
                raise RuntimeError("communication directory timestamp is invalid") from error
            self._users = users
            self._calendar_subjects = frozenset(calendar_subjects)
            self._helpdesk_users = helpdesk_users
            self._generated_at = generated_at
            self._mtime_ns = mtime_ns
            return self._users
