from __future__ import annotations

import hmac
import json
import secrets
import sqlite3
import time
from pathlib import Path
from typing import Any

from .security import SecretBox, compact_json


SCHEMA = """
PRAGMA journal_mode=WAL;
PRAGMA foreign_keys=ON;

CREATE TABLE IF NOT EXISTS users (
    subject TEXT PRIMARY KEY,
    email TEXT NOT NULL,
    display_name TEXT NOT NULL,
    active INTEGER NOT NULL,
    verified_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS push_registrations (
    device_id TEXT PRIMARY KEY,
    subject TEXT NOT NULL REFERENCES users(subject) ON DELETE CASCADE,
    installation_id_ciphertext TEXT NOT NULL,
    agent_token_ciphertext TEXT NOT NULL,
    key_id TEXT NOT NULL UNIQUE,
    public_jwk TEXT NOT NULL,
    mode TEXT NOT NULL CHECK (mode IN ('personal', 'shared')),
    privacy TEXT NOT NULL CHECK (privacy IN ('minimal', 'standard', 'detailed')),
    app_version TEXT NOT NULL,
    updated_at INTEGER NOT NULL,
    last_verified_at INTEGER NOT NULL
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
    device_id TEXT NOT NULL,
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

PRAGMA user_version=2;
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
        self._migrate_legacy_device_store()
        with self._connect() as connection:
            connection.executescript(SCHEMA)

    def _connect(self) -> sqlite3.Connection:
        connection = sqlite3.connect(self.path, timeout=15, factory=ClosingConnection)
        connection.row_factory = sqlite3.Row
        connection.execute("PRAGMA foreign_keys=ON")
        return connection

    def _migrate_legacy_device_store(self) -> None:
        if not self.path.exists():
            return
        connection = sqlite3.connect(self.path, timeout=15)
        try:
            tables = {
                row[0]
                for row in connection.execute("SELECT name FROM sqlite_master WHERE type='table'")
            }
            if "devices" not in tables:
                return
            registrations = connection.execute("SELECT COUNT(*) FROM push_registrations").fetchone()[0]
            if registrations:
                raise RuntimeError(
                    "legacy device registrations exist; back up the database and re-register "
                    "them through Authentik Endpoint Devices before schema migration"
                )
            connection.execute("PRAGMA foreign_keys=OFF")
            connection.executescript(
                """
                BEGIN IMMEDIATE;
                CREATE TABLE push_registrations_v2 (
                    device_id TEXT PRIMARY KEY,
                    subject TEXT NOT NULL REFERENCES users(subject) ON DELETE CASCADE,
                    installation_id_ciphertext TEXT NOT NULL,
                    agent_token_ciphertext TEXT NOT NULL,
                    key_id TEXT NOT NULL UNIQUE,
                    public_jwk TEXT NOT NULL,
                    mode TEXT NOT NULL CHECK (mode IN ('personal', 'shared')),
                    privacy TEXT NOT NULL CHECK (privacy IN ('minimal', 'standard', 'detailed')),
                    app_version TEXT NOT NULL,
                    updated_at INTEGER NOT NULL,
                    last_verified_at INTEGER NOT NULL
                );
                CREATE TABLE event_deliveries_v2 (
                    event_id TEXT NOT NULL REFERENCES notification_events(event_id) ON DELETE CASCADE,
                    device_id TEXT NOT NULL,
                    delivered_at INTEGER NOT NULL,
                    PRIMARY KEY(event_id, device_id)
                );
                INSERT INTO event_deliveries_v2 SELECT event_id, device_id, delivered_at FROM event_deliveries;
                DROP TABLE event_deliveries;
                DROP TABLE push_registrations;
                DROP TABLE enrollment_tokens;
                DROP TABLE devices;
                ALTER TABLE push_registrations_v2 RENAME TO push_registrations;
                ALTER TABLE event_deliveries_v2 RENAME TO event_deliveries;
                PRAGMA user_version=2;
                COMMIT;
                """
            )
        except Exception:
            connection.rollback()
            raise
        finally:
            connection.execute("PRAGMA foreign_keys=ON")
            connection.close()

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
        *,
        device_id: str,
        subject: str,
        installation_id: str,
        agent_token: str,
        key_id: str,
        public_jwk: dict[str, Any],
        mode: str,
        privacy: str,
        app_version: str,
    ) -> None:
        if mode not in {"personal", "shared"}:
            raise ValueError("invalid device mode")
        if mode == "shared":
            privacy = "minimal"
        now = int(time.time())
        with self._connect() as connection:
            connection.execute("BEGIN IMMEDIATE")
            existing = connection.execute(
                "SELECT subject, mode FROM push_registrations WHERE device_id = ?",
                (device_id,),
            ).fetchone()
            if existing and existing["mode"] == "personal" and existing["subject"] != subject:
                raise PermissionError("personal device is assigned to a different user")
            connection.execute(
                """
                INSERT INTO push_registrations(
                    device_id, subject, installation_id_ciphertext, agent_token_ciphertext,
                    key_id, public_jwk, mode, privacy, app_version, updated_at, last_verified_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(device_id) DO UPDATE SET
                    subject=excluded.subject,
                    installation_id_ciphertext=excluded.installation_id_ciphertext,
                    agent_token_ciphertext=excluded.agent_token_ciphertext,
                    key_id=excluded.key_id,
                    public_jwk=excluded.public_jwk,
                    mode=excluded.mode,
                    privacy=excluded.privacy,
                    app_version=excluded.app_version,
                    updated_at=excluded.updated_at,
                    last_verified_at=excluded.last_verified_at
                """,
                (
                    device_id,
                    subject,
                    self.secret_box.encrypt(installation_id),
                    self.secret_box.encrypt(agent_token),
                    key_id,
                    compact_json(public_jwk),
                    mode,
                    privacy,
                    app_version,
                    now,
                    now,
                ),
            )

    def unregister_push(self, device_id: str, subject: str) -> None:
        with self._connect() as connection:
            connection.execute(
                "DELETE FROM push_registrations WHERE device_id = ? AND subject = ?",
                (device_id, subject),
            )

    def remove_registration(self, device_id: str) -> None:
        with self._connect() as connection:
            connection.execute("DELETE FROM push_registrations WHERE device_id = ?", (device_id,))

    def get_registration(self, device_id: str) -> dict[str, Any] | None:
        with self._connect() as connection:
            row = connection.execute(
                """
                SELECT r.* FROM push_registrations r
                JOIN users u ON u.subject = r.subject
                WHERE r.device_id = ? AND u.active = 1
                """,
                (device_id,),
            ).fetchone()
        return self._decode_registration(row)

    def registrations_for_subject(self, subject: str) -> list[dict[str, Any]]:
        with self._connect() as connection:
            rows = connection.execute(
                """
                SELECT r.* FROM push_registrations r
                JOIN users u ON u.subject = r.subject
                WHERE r.subject = ? AND u.active = 1
                """,
                (subject,),
            ).fetchall()
        return [value for row in rows if (value := self._decode_registration(row)) is not None]

    def _decode_registration(self, row: sqlite3.Row | None) -> dict[str, Any] | None:
        if row is None:
            return None
        value = dict(row)
        value["installation_id"] = self.secret_box.decrypt(value.pop("installation_id_ciphertext"))
        value["agent_token"] = self.secret_box.decrypt(value.pop("agent_token_ciphertext"))
        value["public_jwk"] = json.loads(value["public_jwk"])
        return value

    def remove_registration_by_token(self, installation_id: str) -> None:
        with self._connect() as connection:
            rows = connection.execute(
                "SELECT device_id, installation_id_ciphertext FROM push_registrations"
            ).fetchall()
            for row in rows:
                stored = self.secret_box.decrypt(row["installation_id_ciphertext"])
                if hmac.compare_digest(stored, installation_id):
                    connection.execute(
                        "DELETE FROM push_registrations WHERE device_id = ?", (row["device_id"],)
                    )

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
            row = connection.execute(
                "SELECT * FROM notification_events WHERE event_id = ?", (event_id,)
            ).fetchone()
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
                SELECT e.*, r.privacy, r.mode
                FROM notification_events e
                JOIN push_registrations r ON r.subject = e.subject AND r.device_id = ?
                JOIN users u ON u.subject = e.subject
                WHERE e.event_id = ? AND u.active = 1
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

    def touch_registration(self, device_id: str) -> None:
        with self._connect() as connection:
            connection.execute(
                "UPDATE push_registrations SET last_verified_at = ? WHERE device_id = ?",
                (int(time.time()), device_id),
            )

    def create_handoff(
        self, subject: str, source_device_id: str | None, target: str, room_token: str, ttl: int
    ) -> str:
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
