from __future__ import annotations

import hashlib
import hmac
import json
import secrets
import sqlite3
import time
import uuid
from pathlib import Path
from typing import Any

from .security import SecretBox, compact_json


SCHEMA = """
PRAGMA journal_mode=WAL;
PRAGMA foreign_keys=ON;

CREATE TABLE IF NOT EXISTS enrollment_tokens (
    token_hash TEXT PRIMARY KEY,
    mode TEXT NOT NULL CHECK (mode IN ('personal', 'shared')),
    expires_at INTEGER NOT NULL,
    auto_trust INTEGER NOT NULL DEFAULT 0,
    used_at INTEGER
);

CREATE TABLE IF NOT EXISTS devices (
    device_id TEXT PRIMARY KEY,
    key_id TEXT NOT NULL UNIQUE,
    public_jwk TEXT NOT NULL,
    mode TEXT NOT NULL CHECK (mode IN ('personal', 'shared')),
    status TEXT NOT NULL CHECK (status IN ('pending', 'trusted', 'blocked')),
    user_subject TEXT,
    device_name TEXT NOT NULL,
    platform TEXT NOT NULL,
    os_version TEXT NOT NULL,
    app_version TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    last_seen_at INTEGER
);

CREATE TABLE IF NOT EXISTS users (
    subject TEXT PRIMARY KEY,
    email TEXT NOT NULL,
    display_name TEXT NOT NULL,
    active INTEGER NOT NULL,
    verified_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS push_registrations (
    device_id TEXT PRIMARY KEY REFERENCES devices(device_id) ON DELETE CASCADE,
    subject TEXT NOT NULL REFERENCES users(subject) ON DELETE CASCADE,
    installation_id_ciphertext TEXT NOT NULL,
    privacy TEXT NOT NULL CHECK (privacy IN ('minimal', 'standard', 'detailed')),
    app_version TEXT NOT NULL,
    updated_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS notification_events (
    event_id TEXT PRIMARY KEY,
    source TEXT NOT NULL,
    source_event_id TEXT NOT NULL,
    subject TEXT NOT NULL,
    event_type TEXT NOT NULL CHECK (event_type IN ('open_mail', 'open_calendar', 'open_talk')),
    title TEXT NOT NULL,
    summary TEXT NOT NULL,
    preview TEXT NOT NULL,
    display_at TEXT,
    expires_at TEXT,
    expires_epoch INTEGER,
    deliver_epoch INTEGER NOT NULL,
    delivered_at INTEGER,
    revision INTEGER NOT NULL,
    created_at INTEGER NOT NULL,
    UNIQUE(source, source_event_id)
);

CREATE TABLE IF NOT EXISTS request_nonces (
    device_id TEXT NOT NULL,
    nonce TEXT NOT NULL,
    expires_at INTEGER NOT NULL,
    PRIMARY KEY(device_id, nonce)
);

CREATE TABLE IF NOT EXISTS event_deliveries (
    event_id TEXT NOT NULL REFERENCES notification_events(event_id) ON DELETE CASCADE,
    device_id TEXT NOT NULL REFERENCES devices(device_id) ON DELETE CASCADE,
    delivered_at INTEGER NOT NULL,
    PRIMARY KEY(event_id, device_id)
);

CREATE TABLE IF NOT EXISTS handoffs (
    handoff_id TEXT PRIMARY KEY,
    subject TEXT NOT NULL,
    source_device_id TEXT,
    target_device_id TEXT NOT NULL,
    room_token TEXT NOT NULL,
    expires_at INTEGER NOT NULL,
    status TEXT NOT NULL,
    created_at INTEGER NOT NULL
);
"""


class ClosingConnection(sqlite3.Connection):
    def __exit__(self, exc_type: object, exc_value: object, traceback: object) -> bool:
        try:
            return bool(super().__exit__(exc_type, exc_value, traceback))
        finally:
            self.close()


class Store:
    def __init__(self, path: Path, hmac_secret: bytes, secret_box: SecretBox):
        self.path = path
        self.hmac_secret = hmac_secret
        self.secret_box = secret_box
        path.parent.mkdir(parents=True, exist_ok=True)
        with self._connect() as connection:
            connection.executescript(SCHEMA)

    def _connect(self) -> sqlite3.Connection:
        connection = sqlite3.connect(self.path, timeout=15, factory=ClosingConnection)
        connection.row_factory = sqlite3.Row
        connection.execute("PRAGMA foreign_keys=ON")
        return connection

    def _token_hash(self, token: str) -> str:
        return hmac.new(self.hmac_secret, token.encode(), hashlib.sha256).hexdigest()

    def create_enrollment_token(self, mode: str, ttl_seconds: int, auto_trust: bool) -> str:
        if mode not in {"personal", "shared"}:
            raise ValueError("invalid device mode")
        if not 60 <= ttl_seconds <= 86_400:
            raise ValueError("enrollment token TTL must be between 60 and 86400 seconds")
        token = secrets.token_urlsafe(32)
        with self._connect() as connection:
            connection.execute(
                "INSERT INTO enrollment_tokens(token_hash, mode, expires_at, auto_trust) VALUES(?, ?, ?, ?)",
                (self._token_hash(token), mode, int(time.time()) + ttl_seconds, int(auto_trust)),
            )
        return token

    def enroll_device(self, token: str, payload: dict[str, Any]) -> dict[str, Any]:
        now = int(time.time())
        with self._connect() as connection:
            connection.execute("BEGIN IMMEDIATE")
            row = connection.execute(
                "SELECT mode, expires_at, auto_trust, used_at FROM enrollment_tokens WHERE token_hash = ?",
                (self._token_hash(token),),
            ).fetchone()
            if row is None or row["used_at"] is not None or row["expires_at"] <= now:
                raise ValueError("enrollment token is invalid, expired, or already used")
            if row["mode"] != payload["mode"]:
                raise ValueError("enrollment token does not match the requested mode")
            connection.execute(
                "UPDATE enrollment_tokens SET used_at = ? WHERE token_hash = ?",
                (now, self._token_hash(token)),
            )
            device_id = str(uuid.uuid4())
            status = "trusted" if row["auto_trust"] else "pending"
            connection.execute(
                """
                INSERT INTO devices(
                    device_id, key_id, public_jwk, mode, status, user_subject,
                    device_name, platform, os_version, app_version, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, NULL, ?, ?, ?, ?, ?, ?)
                """,
                (
                    device_id,
                    payload["key_id"],
                    compact_json(payload["public_key_jwk"]),
                    payload["mode"],
                    status,
                    payload["device_name"],
                    payload["platform"],
                    payload["os_version"],
                    payload["app_version"],
                    now,
                    now,
                ),
            )
        return {"device_id": device_id, "status": status}

    def list_devices(self) -> list[dict[str, Any]]:
        with self._connect() as connection:
            rows = connection.execute(
                """
                SELECT device_id, key_id, mode, status, user_subject, device_name,
                       platform, os_version, app_version, created_at, updated_at, last_seen_at
                FROM devices ORDER BY created_at DESC
                """
            ).fetchall()
        return [dict(row) for row in rows]

    def set_device_status(self, device_id: str, status: str) -> bool:
        if status not in {"pending", "trusted", "blocked"}:
            raise ValueError("invalid device status")
        now = int(time.time())
        with self._connect() as connection:
            cursor = connection.execute(
                "UPDATE devices SET status = ?, updated_at = ? WHERE device_id = ?",
                (status, now, device_id),
            )
            if status != "trusted":
                connection.execute("DELETE FROM push_registrations WHERE device_id = ?", (device_id,))
            return cursor.rowcount == 1

    def get_device(self, device_id: str) -> dict[str, Any] | None:
        with self._connect() as connection:
            row = connection.execute("SELECT * FROM devices WHERE device_id = ?", (device_id,)).fetchone()
        if row is None:
            return None
        value = dict(row)
        value["public_jwk"] = json.loads(value["public_jwk"])
        return value

    def upsert_user(self, subject: str, email: str, display_name: str, active: bool = True) -> None:
        now = int(time.time())
        with self._connect() as connection:
            connection.execute(
                """
                INSERT INTO users(subject, email, display_name, active, verified_at)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT(subject) DO UPDATE SET
                    email=excluded.email,
                    display_name=excluded.display_name,
                    active=excluded.active,
                    verified_at=excluded.verified_at
                """,
                (subject, email, display_name, int(active), now),
            )

    def set_user_active(self, subject: str, active: bool) -> bool:
        with self._connect() as connection:
            cursor = connection.execute(
                "UPDATE users SET active = ?, verified_at = ? WHERE subject = ?",
                (int(active), int(time.time()), subject),
            )
            if not active:
                connection.execute("DELETE FROM push_registrations WHERE subject = ?", (subject,))
            return cursor.rowcount == 1

    def register_push(
        self,
        device_id: str,
        subject: str,
        installation_id: str,
        privacy: str,
        app_version: str,
    ) -> None:
        now = int(time.time())
        with self._connect() as connection:
            connection.execute("BEGIN IMMEDIATE")
            device = connection.execute(
                "SELECT mode, status, user_subject FROM devices WHERE device_id = ?",
                (device_id,),
            ).fetchone()
            if device is None or device["status"] != "trusted":
                raise PermissionError("device is not trusted")
            if device["mode"] == "personal":
                if device["user_subject"] not in {None, subject}:
                    raise PermissionError("device is assigned to a different user")
                if device["user_subject"] is None:
                    connection.execute(
                        "UPDATE devices SET user_subject = ?, updated_at = ? WHERE device_id = ?",
                        (subject, now, device_id),
                    )
            else:
                privacy = "minimal"
            connection.execute(
                """
                INSERT INTO push_registrations(
                    device_id, subject, installation_id_ciphertext, privacy, app_version, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT(device_id) DO UPDATE SET
                    subject=excluded.subject,
                    installation_id_ciphertext=excluded.installation_id_ciphertext,
                    privacy=excluded.privacy,
                    app_version=excluded.app_version,
                    updated_at=excluded.updated_at
                """,
                (device_id, subject, self.secret_box.encrypt(installation_id), privacy, app_version, now),
            )

    def unregister_push(self, device_id: str, subject: str) -> None:
        with self._connect() as connection:
            connection.execute(
                "DELETE FROM push_registrations WHERE device_id = ? AND subject = ?",
                (device_id, subject),
            )

    def registrations_for_subject(self, subject: str) -> list[dict[str, Any]]:
        with self._connect() as connection:
            rows = connection.execute(
                """
                SELECT r.device_id, r.subject, r.installation_id_ciphertext, r.privacy,
                       d.mode, d.status
                FROM push_registrations r
                JOIN devices d ON d.device_id = r.device_id
                JOIN users u ON u.subject = r.subject
                WHERE r.subject = ? AND d.status = 'trusted' AND u.active = 1
                """,
                (subject,),
            ).fetchall()
        values = []
        for row in rows:
            value = dict(row)
            value["installation_id"] = self.secret_box.decrypt(value.pop("installation_id_ciphertext"))
            values.append(value)
        return values

    def remove_registration_by_token(self, installation_id: str) -> None:
        with self._connect() as connection:
            rows = connection.execute(
                "SELECT device_id, installation_id_ciphertext FROM push_registrations"
            ).fetchall()
            for row in rows:
                if hmac.compare_digest(self.secret_box.decrypt(row["installation_id_ciphertext"]), installation_id):
                    connection.execute("DELETE FROM push_registrations WHERE device_id = ?", (row["device_id"],))

    def put_event(self, event: dict[str, Any]) -> tuple[dict[str, Any], bool]:
        now = int(time.time())
        event_id = secrets.token_urlsafe(20)
        with self._connect() as connection:
            connection.execute("BEGIN IMMEDIATE")
            existing = connection.execute(
                "SELECT * FROM notification_events WHERE source = ? AND source_event_id = ?",
                (event["source"], event["source_event_id"]),
            ).fetchone()
            if existing is not None:
                return dict(existing), False
            connection.execute(
                """
                INSERT INTO notification_events(
                    event_id, source, source_event_id, subject, event_type, title,
                    summary, preview, display_at, expires_at, expires_epoch,
                    deliver_epoch, delivered_at, revision, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, 1, ?)
                """,
                (
                    event_id,
                    event["source"],
                    event["source_event_id"],
                    event["subject"],
                    event["event_type"],
                    event["title"],
                    event["summary"],
                    event["preview"],
                    event.get("display_at"),
                    event.get("expires_at"),
                    event.get("expires_epoch"),
                    event["deliver_epoch"],
                    now,
                ),
            )
            row = connection.execute("SELECT * FROM notification_events WHERE event_id = ?", (event_id,)).fetchone()
        return dict(row), True

    def due_events(self, limit: int = 100) -> list[dict[str, Any]]:
        now = int(time.time())
        with self._connect() as connection:
            rows = connection.execute(
                """
                SELECT * FROM notification_events
                WHERE delivered_at IS NULL
                  AND deliver_epoch <= ?
                  AND (expires_epoch IS NULL OR expires_epoch > ?)
                ORDER BY deliver_epoch ASC
                LIMIT ?
                """,
                (now, now, limit),
            ).fetchall()
        return [dict(row) for row in rows]

    def mark_event_delivered(self, event_id: str) -> None:
        with self._connect() as connection:
            connection.execute(
                "UPDATE notification_events SET delivered_at = ? WHERE event_id = ? AND delivered_at IS NULL",
                (int(time.time()), event_id),
            )

    def delivery_exists(self, event_id: str, device_id: str) -> bool:
        with self._connect() as connection:
            row = connection.execute(
                "SELECT 1 FROM event_deliveries WHERE event_id = ? AND device_id = ?",
                (event_id, device_id),
            ).fetchone()
        return row is not None

    def record_delivery(self, event_id: str, device_id: str) -> None:
        with self._connect() as connection:
            connection.execute(
                """
                INSERT OR IGNORE INTO event_deliveries(event_id, device_id, delivered_at)
                VALUES (?, ?, ?)
                """,
                (event_id, device_id, int(time.time())),
            )

    def get_event_for_device(self, event_id: str, device_id: str) -> tuple[dict[str, Any], str] | None:
        with self._connect() as connection:
            row = connection.execute(
                """
                SELECT e.*, r.privacy, d.mode, d.public_jwk, d.key_id, d.status
                FROM notification_events e
                JOIN push_registrations r ON r.subject = e.subject AND r.device_id = ?
                JOIN devices d ON d.device_id = r.device_id
                JOIN users u ON u.subject = e.subject
                WHERE e.event_id = ? AND d.status = 'trusted' AND u.active = 1
                """,
                (device_id, event_id),
            ).fetchone()
        if row is None:
            return None
        value = dict(row)
        return value, value["privacy"]

    def consume_nonce(self, device_id: str, nonce: str, valid_until: int) -> bool:
        now = int(time.time())
        with self._connect() as connection:
            connection.execute("DELETE FROM request_nonces WHERE expires_at <= ?", (now,))
            try:
                connection.execute(
                    "INSERT INTO request_nonces(device_id, nonce, expires_at) VALUES (?, ?, ?)",
                    (device_id, nonce, valid_until),
                )
            except sqlite3.IntegrityError:
                return False
        return True

    def touch_device(self, device_id: str) -> None:
        with self._connect() as connection:
            connection.execute(
                "UPDATE devices SET last_seen_at = ?, updated_at = ? WHERE device_id = ?",
                (int(time.time()), int(time.time()), device_id),
            )

    def create_handoff(self, subject: str, source_device_id: str | None, target: str, room_token: str, ttl: int) -> str:
        handoff_id = secrets.token_urlsafe(18)
        now = int(time.time())
        with self._connect() as connection:
            connection.execute(
                """
                INSERT INTO handoffs(
                    handoff_id, subject, source_device_id, target_device_id,
                    room_token, expires_at, status, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, 'accepted', ?)
                """,
                (handoff_id, subject, source_device_id, target, room_token, now + ttl, now),
            )
        return handoff_id
