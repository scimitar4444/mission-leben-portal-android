from __future__ import annotations

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
    push_provider TEXT NOT NULL DEFAULT 'none',
    ntfy_topic TEXT NOT NULL DEFAULT '',
    ntfy_reader_username TEXT NOT NULL DEFAULT '',
    ntfy_writer_username TEXT NOT NULL DEFAULT '',
    ntfy_publish_token_ciphertext TEXT NOT NULL DEFAULT '',
    agent_token_ciphertext TEXT NOT NULL,
    key_id TEXT NOT NULL UNIQUE,
    public_jwk TEXT NOT NULL,
    mode TEXT NOT NULL CHECK (mode IN ('personal', 'shared')),
    privacy TEXT NOT NULL CHECK (privacy IN ('minimal', 'standard', 'detailed')),
    calendar_reminder_minutes INTEGER NOT NULL DEFAULT 15
        CHECK (calendar_reminder_minutes IN (5, 10, 15, 30)),
    communication_enabled INTEGER NOT NULL DEFAULT 1 CHECK (communication_enabled IN (0, 1)),
    quiet_hours_enabled INTEGER NOT NULL DEFAULT 0 CHECK (quiet_hours_enabled IN (0, 1)),
    quiet_start_minutes INTEGER NOT NULL DEFAULT 1320 CHECK (quiet_start_minutes BETWEEN 0 AND 1439),
    quiet_end_minutes INTEGER NOT NULL DEFAULT 360 CHECK (quiet_end_minutes BETWEEN 0 AND 1439),
    timezone TEXT NOT NULL DEFAULT 'Europe/Berlin',
    app_version TEXT NOT NULL,
    push_enabled INTEGER NOT NULL DEFAULT 1,
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
    target_id TEXT NOT NULL DEFAULT '',
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

CREATE TABLE IF NOT EXISTS event_delivery_queue (
    event_id TEXT NOT NULL REFERENCES notification_events(event_id) ON DELETE CASCADE,
    device_id TEXT NOT NULL REFERENCES push_registrations(device_id) ON DELETE CASCADE,
    deliver_epoch INTEGER NOT NULL,
    created_at INTEGER NOT NULL,
    PRIMARY KEY(event_id, device_id)
);

CREATE INDEX IF NOT EXISTS event_delivery_queue_due
ON event_delivery_queue(deliver_epoch, event_id, device_id);

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

CREATE TABLE IF NOT EXISTS auth_requests (
    request_id TEXT PRIMARY KEY,
    subject TEXT NOT NULL,
    application TEXT NOT NULL,
    domain TEXT NOT NULL,
    display_username TEXT NOT NULL,
    source_ip TEXT NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('pending', 'approved', 'denied', 'expired')),
    approving_device_id TEXT,
    created_at INTEGER NOT NULL,
    expires_at INTEGER NOT NULL,
    completed_at INTEGER
);

CREATE TABLE IF NOT EXISTS announcement_cache (
    subject TEXT PRIMARY KEY REFERENCES users(subject) ON DELETE CASCADE,
    payload TEXT NOT NULL,
    fetched_at INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS auth_requests_subject_status
ON auth_requests(subject, status, expires_at);

PRAGMA user_version=8;
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
            columns = {
                row["name"]
                for row in connection.execute("PRAGMA table_info(push_registrations)").fetchall()
            }
            if "push_enabled" not in columns:
                connection.execute(
                    "ALTER TABLE push_registrations ADD COLUMN push_enabled INTEGER NOT NULL DEFAULT 1"
                )
            additions = {
                "push_provider": "TEXT NOT NULL DEFAULT 'none'",
                "ntfy_topic": "TEXT NOT NULL DEFAULT ''",
                "ntfy_reader_username": "TEXT NOT NULL DEFAULT ''",
                "ntfy_writer_username": "TEXT NOT NULL DEFAULT ''",
                "ntfy_publish_token_ciphertext": "TEXT NOT NULL DEFAULT ''",
                "calendar_reminder_minutes": "INTEGER NOT NULL DEFAULT 15 CHECK (calendar_reminder_minutes IN (5, 10, 15, 30))",
                "communication_enabled": "INTEGER NOT NULL DEFAULT 1 CHECK (communication_enabled IN (0, 1))",
                "quiet_hours_enabled": "INTEGER NOT NULL DEFAULT 0 CHECK (quiet_hours_enabled IN (0, 1))",
                "quiet_start_minutes": "INTEGER NOT NULL DEFAULT 1320 CHECK (quiet_start_minutes BETWEEN 0 AND 1439)",
                "quiet_end_minutes": "INTEGER NOT NULL DEFAULT 360 CHECK (quiet_end_minutes BETWEEN 0 AND 1439)",
                "timezone": "TEXT NOT NULL DEFAULT 'Europe/Berlin'",
            }
            for name, definition in additions.items():
                if name not in columns:
                    connection.execute(
                        f"ALTER TABLE push_registrations ADD COLUMN {name} {definition}"
                    )
            event_columns = {
                row["name"]
                for row in connection.execute("PRAGMA table_info(notification_events)").fetchall()
            }
            if "target_id" not in event_columns:
                connection.execute(
                    "ALTER TABLE notification_events ADD COLUMN target_id TEXT NOT NULL DEFAULT ''"
                )
            connection.execute(
                """
                INSERT OR IGNORE INTO event_delivery_queue(event_id, device_id, deliver_epoch, created_at)
                SELECT e.event_id, r.device_id, e.deliver_epoch, ?
                FROM notification_events e
                JOIN push_registrations r ON r.subject = e.subject AND r.push_enabled = 1
                LEFT JOIN event_deliveries d
                  ON d.event_id = e.event_id AND d.device_id = r.device_id
                WHERE e.delivered_at IS NULL AND d.event_id IS NULL
                """,
                (int(time.time()),),
            )
            connection.execute("PRAGMA user_version=8")

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
                    push_provider TEXT NOT NULL DEFAULT 'none',
                    ntfy_topic TEXT NOT NULL DEFAULT '',
                    ntfy_reader_username TEXT NOT NULL DEFAULT '',
                    ntfy_writer_username TEXT NOT NULL DEFAULT '',
                    ntfy_publish_token_ciphertext TEXT NOT NULL DEFAULT '',
                    agent_token_ciphertext TEXT NOT NULL,
                    key_id TEXT NOT NULL UNIQUE,
                    public_jwk TEXT NOT NULL,
                    mode TEXT NOT NULL CHECK (mode IN ('personal', 'shared')),
                    privacy TEXT NOT NULL CHECK (privacy IN ('minimal', 'standard', 'detailed')),
                    calendar_reminder_minutes INTEGER NOT NULL DEFAULT 15
                        CHECK (calendar_reminder_minutes IN (5, 10, 15, 30)),
                    communication_enabled INTEGER NOT NULL DEFAULT 1 CHECK (communication_enabled IN (0, 1)),
                    quiet_hours_enabled INTEGER NOT NULL DEFAULT 0 CHECK (quiet_hours_enabled IN (0, 1)),
                    quiet_start_minutes INTEGER NOT NULL DEFAULT 1320 CHECK (quiet_start_minutes BETWEEN 0 AND 1439),
                    quiet_end_minutes INTEGER NOT NULL DEFAULT 360 CHECK (quiet_end_minutes BETWEEN 0 AND 1439),
                    timezone TEXT NOT NULL DEFAULT 'Europe/Berlin',
                    app_version TEXT NOT NULL,
                    push_enabled INTEGER NOT NULL DEFAULT 1,
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
        if not active:
            self.offboard_subject(subject)
            return True
        with self._connect() as connection:
            cursor = connection.execute(
                "UPDATE users SET active = ?, verified_at = ? WHERE subject = ?",
                (int(active), int(time.time()), subject),
            )
            return cursor.rowcount == 1

    def user_active_state(self, subject: str) -> bool | None:
        with self._connect() as connection:
            row = connection.execute(
                "SELECT active FROM users WHERE subject = ?", (subject,)
            ).fetchone()
        return bool(row["active"]) if row is not None else None

    def offboard_subject(self, subject: str, *, dry_run: bool = False) -> dict[str, Any]:
        now = int(time.time())
        with self._connect() as connection:
            connection.execute("BEGIN IMMEDIATE")
            counts = {
                "registrations": connection.execute(
                    "SELECT COUNT(*) FROM push_registrations WHERE subject = ?", (subject,)
                ).fetchone()[0],
                "nonces": connection.execute(
                    """
                    SELECT COUNT(*) FROM request_nonces
                    WHERE device_id IN (
                        SELECT device_id FROM push_registrations WHERE subject = ?
                    )
                    """,
                    (subject,),
                ).fetchone()[0],
                "events": connection.execute(
                    "SELECT COUNT(*) FROM notification_events WHERE subject = ?", (subject,)
                ).fetchone()[0],
                "deliveries": connection.execute(
                    """
                    SELECT COUNT(*) FROM event_deliveries
                    WHERE event_id IN (
                        SELECT event_id FROM notification_events WHERE subject = ?
                    )
                    """,
                    (subject,),
                ).fetchone()[0],
                "handoffs": connection.execute(
                    "SELECT COUNT(*) FROM handoffs WHERE subject = ?", (subject,)
                ).fetchone()[0],
                "auth_requests": connection.execute(
                    "SELECT COUNT(*) FROM auth_requests WHERE subject = ?", (subject,)
                ).fetchone()[0],
                "announcement_cache": connection.execute(
                    "SELECT COUNT(*) FROM announcement_cache WHERE subject = ?", (subject,)
                ).fetchone()[0],
            }
            user_exists = connection.execute(
                "SELECT 1 FROM users WHERE subject = ?", (subject,)
            ).fetchone() is not None
            if dry_run:
                return {
                    "subject_known": user_exists,
                    "applied": False,
                    **counts,
                }

            connection.execute(
                """
                DELETE FROM request_nonces
                WHERE device_id IN (
                    SELECT device_id FROM push_registrations WHERE subject = ?
                )
                """,
                (subject,),
            )
            connection.execute("DELETE FROM push_registrations WHERE subject = ?", (subject,))
            connection.execute("DELETE FROM notification_events WHERE subject = ?", (subject,))
            connection.execute("DELETE FROM handoffs WHERE subject = ?", (subject,))
            connection.execute("DELETE FROM auth_requests WHERE subject = ?", (subject,))
            connection.execute("DELETE FROM announcement_cache WHERE subject = ?", (subject,))
            connection.execute(
                """
                INSERT INTO users(subject, email, display_name, active, verified_at)
                VALUES (?, '', '', 0, ?)
                ON CONFLICT(subject) DO UPDATE SET
                    email='',
                    display_name='',
                    active=0,
                    verified_at=excluded.verified_at
                """,
                (subject, now),
            )
            return {
                "subject_known": user_exists,
                "applied": True,
                **counts,
            }

    def put_announcement_cache(
        self, subject: str, announcements: list[dict[str, Any]], fetched_at: int
    ) -> None:
        payload = json.dumps(announcements, ensure_ascii=False, separators=(",", ":"))
        with self._connect() as connection:
            connection.execute(
                """
                INSERT INTO announcement_cache(subject, payload, fetched_at)
                VALUES (?, ?, ?)
                ON CONFLICT(subject) DO UPDATE SET
                    payload=excluded.payload,
                    fetched_at=excluded.fetched_at
                """,
                (subject, payload, fetched_at),
            )

    def get_announcement_cache(self, subject: str) -> tuple[list[dict[str, Any]], int] | None:
        with self._connect() as connection:
            row = connection.execute(
                "SELECT payload, fetched_at FROM announcement_cache WHERE subject = ?",
                (subject,),
            ).fetchone()
        if row is None:
            return None
        try:
            payload = json.loads(row["payload"])
        except (TypeError, json.JSONDecodeError):
            return None
        if not isinstance(payload, list) or not all(isinstance(item, dict) for item in payload):
            return None
        return payload, int(row["fetched_at"])

    def register_push(
        self,
        *,
        device_id: str,
        subject: str,
        subscribe_token: str,
        publish_token: str,
        topic: str,
        reader_username: str,
        writer_username: str,
        agent_token: str,
        key_id: str,
        public_jwk: dict[str, Any],
        mode: str,
        privacy: str,
        app_version: str,
        calendar_reminder_minutes: int = 15,
        communication_enabled: bool = True,
        quiet_hours_enabled: bool = False,
        quiet_start_minutes: int = 1320,
        quiet_end_minutes: int = 360,
        timezone_name: str = "Europe/Berlin",
    ) -> None:
        if mode not in {"personal", "shared"}:
            raise ValueError("invalid device mode")
        if mode == "shared":
            privacy = "minimal"
            calendar_reminder_minutes = 15
            communication_enabled = True
            quiet_hours_enabled = False
        if calendar_reminder_minutes not in {5, 10, 15, 30}:
            raise ValueError("invalid calendar reminder")
        if quiet_start_minutes not in range(1440) or quiet_end_minutes not in range(1440):
            raise ValueError("invalid quiet hours")
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
                    device_id, subject, installation_id_ciphertext, push_provider,
                    ntfy_topic, ntfy_reader_username, ntfy_writer_username,
                    ntfy_publish_token_ciphertext, agent_token_ciphertext,
                    key_id, public_jwk, mode, privacy, calendar_reminder_minutes,
                    communication_enabled, quiet_hours_enabled,
                    quiet_start_minutes, quiet_end_minutes, timezone,
                    app_version, push_enabled,
                    updated_at, last_verified_at
                ) VALUES (?, ?, ?, 'ntfy', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?)
                ON CONFLICT(device_id) DO UPDATE SET
                    subject=excluded.subject,
                    installation_id_ciphertext=excluded.installation_id_ciphertext,
                    push_provider='ntfy',
                    ntfy_topic=excluded.ntfy_topic,
                    ntfy_reader_username=excluded.ntfy_reader_username,
                    ntfy_writer_username=excluded.ntfy_writer_username,
                    ntfy_publish_token_ciphertext=excluded.ntfy_publish_token_ciphertext,
                    agent_token_ciphertext=excluded.agent_token_ciphertext,
                    key_id=excluded.key_id,
                    public_jwk=excluded.public_jwk,
                    mode=excluded.mode,
                    privacy=excluded.privacy,
                    calendar_reminder_minutes=excluded.calendar_reminder_minutes,
                    communication_enabled=excluded.communication_enabled,
                    quiet_hours_enabled=excluded.quiet_hours_enabled,
                    quiet_start_minutes=excluded.quiet_start_minutes,
                    quiet_end_minutes=excluded.quiet_end_minutes,
                    timezone=excluded.timezone,
                    app_version=excluded.app_version,
                    push_enabled=1,
                    updated_at=excluded.updated_at,
                    last_verified_at=excluded.last_verified_at
                """,
                (
                    device_id,
                    subject,
                    self.secret_box.encrypt(subscribe_token),
                    topic,
                    reader_username,
                    writer_username,
                    self.secret_box.encrypt(publish_token),
                    self.secret_box.encrypt(agent_token),
                    key_id,
                    compact_json(public_jwk),
                    mode,
                    privacy,
                    calendar_reminder_minutes,
                    int(communication_enabled),
                    int(quiet_hours_enabled),
                    quiet_start_minutes,
                    quiet_end_minutes,
                    timezone_name,
                    app_version,
                    now,
                    now,
                ),
            )
            connection.execute(
                """
                UPDATE event_delivery_queue
                SET deliver_epoch = (
                    SELECT CAST(strftime('%s', e.display_at) AS INTEGER) - ?
                    FROM notification_events e
                    WHERE e.event_id = event_delivery_queue.event_id
                )
                WHERE device_id = ?
                  AND event_id IN (
                      SELECT event_id FROM notification_events
                      WHERE event_type = 'open_calendar' AND display_at IS NOT NULL
                  )
                """,
                (calendar_reminder_minutes * 60, device_id),
            )
            connection.execute(
                """
                INSERT OR IGNORE INTO event_delivery_queue(
                    event_id, device_id, deliver_epoch, created_at
                )
                SELECT e.event_id, ?,
                       CAST(strftime('%s', e.display_at) AS INTEGER) - ?, ?
                FROM notification_events e
                LEFT JOIN event_deliveries d
                  ON d.event_id = e.event_id AND d.device_id = ?
                WHERE e.subject = ?
                  AND e.event_type = 'open_calendar'
                  AND e.display_at IS NOT NULL
                  AND CAST(strftime('%s', e.display_at) AS INTEGER) > ?
                  AND (e.expires_epoch IS NULL OR e.expires_epoch > ?)
                  AND d.event_id IS NULL
                """,
                (
                    device_id,
                    calendar_reminder_minutes * 60,
                    now,
                    device_id,
                    subject,
                    now,
                    now,
                ),
            )
            connection.execute(
                """
                UPDATE notification_events
                SET delivered_at = NULL
                WHERE event_id IN (
                    SELECT event_id FROM event_delivery_queue WHERE device_id = ?
                )
                """,
                (device_id,),
            )

    def register_auth_channel(
        self,
        *,
        device_id: str,
        subject: str,
        agent_token: str,
        key_id: str,
        public_jwk: dict[str, Any],
        mode: str,
        app_version: str,
    ) -> None:
        if mode != "personal":
            raise PermissionError("login approvals require a personal device")
        now = int(time.time())
        with self._connect() as connection:
            connection.execute("BEGIN IMMEDIATE")
            existing = connection.execute(
                "SELECT subject, mode FROM push_registrations WHERE device_id = ?",
                (device_id,),
            ).fetchone()
            if existing and (existing["mode"] != "personal" or existing["subject"] != subject):
                raise PermissionError("personal device is assigned to a different user")
            connection.execute(
                """
                INSERT INTO push_registrations(
                    device_id, subject, installation_id_ciphertext, agent_token_ciphertext,
                    key_id, public_jwk, mode, privacy, app_version, push_enabled,
                    updated_at, last_verified_at
                ) VALUES (?, ?, ?, ?, ?, ?, 'personal', 'minimal', ?, 0, ?, ?)
                ON CONFLICT(device_id) DO UPDATE SET
                    subject=excluded.subject,
                    agent_token_ciphertext=excluded.agent_token_ciphertext,
                    key_id=excluded.key_id,
                    public_jwk=excluded.public_jwk,
                    mode='personal',
                    app_version=excluded.app_version,
                    updated_at=excluded.updated_at,
                    last_verified_at=excluded.last_verified_at
                """,
                (
                    device_id,
                    subject,
                    self.secret_box.encrypt(""),
                    self.secret_box.encrypt(agent_token),
                    key_id,
                    compact_json(public_jwk),
                    app_version,
                    now,
                    now,
                ),
            )

    def unregister_push(self, device_id: str, subject: str) -> None:
        with self._connect() as connection:
            connection.execute("BEGIN IMMEDIATE")
            connection.execute(
                """
                UPDATE push_registrations
                SET installation_id_ciphertext = ?, push_provider = 'none',
                    ntfy_topic = '', ntfy_reader_username = '', ntfy_writer_username = '',
                    ntfy_publish_token_ciphertext = '', push_enabled = 0, updated_at = ?
                WHERE device_id = ? AND subject = ?
                """,
                (self.secret_box.encrypt(""), int(time.time()), device_id, subject),
            )
            connection.execute(
                "DELETE FROM event_delivery_queue WHERE device_id = ?",
                (device_id,),
            )
            connection.execute(
                """
                UPDATE notification_events
                SET delivered_at = ?
                WHERE delivered_at IS NULL
                  AND NOT EXISTS (
                      SELECT 1 FROM event_delivery_queue q
                      WHERE q.event_id = notification_events.event_id
                  )
                """,
                (int(time.time()),),
            )

    def unregister_auth_channel(self, device_id: str, subject: str) -> None:
        with self._connect() as connection:
            connection.execute(
                "DELETE FROM push_registrations WHERE device_id = ? AND subject = ?",
                (device_id, subject),
            )

    def remove_registration(self, device_id: str) -> None:
        with self._connect() as connection:
            connection.execute("DELETE FROM push_registrations WHERE device_id = ?", (device_id,))

    def registration_for_security_lock(self, device_id: str) -> dict[str, Any] | None:
        """Return a device even if its local user was already marked inactive."""
        with self._connect() as connection:
            row = connection.execute(
                "SELECT * FROM push_registrations WHERE device_id = ?",
                (device_id,),
            ).fetchone()
        return self._decode_registration(row)

    def lock_device(self, device_id: str, *, dry_run: bool = False) -> dict[str, Any]:
        """Remove only one device's communication state, preserving its user and peers."""
        with self._connect() as connection:
            connection.execute("BEGIN IMMEDIATE")
            counts = {
                "registrations": connection.execute(
                    "SELECT COUNT(*) FROM push_registrations WHERE device_id = ?",
                    (device_id,),
                ).fetchone()[0],
                "nonces": connection.execute(
                    "SELECT COUNT(*) FROM request_nonces WHERE device_id = ?",
                    (device_id,),
                ).fetchone()[0],
                "queued_deliveries": connection.execute(
                    "SELECT COUNT(*) FROM event_delivery_queue WHERE device_id = ?",
                    (device_id,),
                ).fetchone()[0],
                "delivery_history": connection.execute(
                    "SELECT COUNT(*) FROM event_deliveries WHERE device_id = ?",
                    (device_id,),
                ).fetchone()[0],
                "handoffs": connection.execute(
                    """
                    SELECT COUNT(*) FROM handoffs
                    WHERE source_device_id = ? OR target_device_id = ?
                    """,
                    (device_id, device_id),
                ).fetchone()[0],
            }
            if dry_run:
                return {"applied": False, **counts}

            connection.execute("DELETE FROM request_nonces WHERE device_id = ?", (device_id,))
            connection.execute("DELETE FROM event_delivery_queue WHERE device_id = ?", (device_id,))
            connection.execute("DELETE FROM event_deliveries WHERE device_id = ?", (device_id,))
            connection.execute(
                "DELETE FROM handoffs WHERE source_device_id = ? OR target_device_id = ?",
                (device_id, device_id),
            )
            connection.execute("DELETE FROM push_registrations WHERE device_id = ?", (device_id,))
            return {"applied": True, **counts}

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
                WHERE r.subject = ? AND u.active = 1 AND r.push_enabled = 1
                """,
                (subject,),
            ).fetchall()
        return [value for row in rows if (value := self._decode_registration(row)) is not None]

    def registrations_for_offboarding(self, subject: str) -> list[dict[str, Any]]:
        """Return push targets even when the local user was already marked inactive."""
        with self._connect() as connection:
            rows = connection.execute(
                """
                SELECT r.* FROM push_registrations r
                WHERE r.subject = ? AND r.push_enabled = 1
                """,
                (subject,),
            ).fetchall()
        return [value for row in rows if (value := self._decode_registration(row)) is not None]

    def auth_registrations_for_subject(self, subject: str) -> list[dict[str, Any]]:
        with self._connect() as connection:
            rows = connection.execute(
                """
                SELECT r.* FROM push_registrations r
                JOIN users u ON u.subject = r.subject
                WHERE r.subject = ? AND u.active = 1 AND r.mode = 'personal'
                """,
                (subject,),
            ).fetchall()
        return [value for row in rows if (value := self._decode_registration(row)) is not None]

    def _decode_registration(self, row: sqlite3.Row | None) -> dict[str, Any] | None:
        if row is None:
            return None
        value = dict(row)
        encrypted_subscription = value.pop("installation_id_ciphertext")
        encrypted_publish = value.pop("ntfy_publish_token_ciphertext", "")
        value["subscribe_token"] = (
            self.secret_box.decrypt(encrypted_subscription) if encrypted_subscription else ""
        )
        value["publish_token"] = (
            self.secret_box.decrypt(encrypted_publish) if encrypted_publish else ""
        )
        value["agent_token"] = self.secret_box.decrypt(value.pop("agent_token_ciphertext"))
        value["public_jwk"] = json.loads(value["public_jwk"])
        return value

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
                # A calendar instance keeps its identity and delivery schedule,
                # but its details can change (or have been imported by an older
                # parser). Refresh those details without queuing a second alert.
                if (
                    existing["event_type"] == event["event_type"] == "open_calendar"
                    and existing["subject"] == event["subject"]
                    and existing["display_at"] == event.get("display_at")
                ):
                    fields = ("title", "summary", "preview", "target_id", "expires_at", "expires_epoch")
                    values = tuple(event.get(field, "" if field == "target_id" else None) for field in fields)
                    if values != tuple(existing[field] for field in fields):
                        connection.execute(
                            """
                            UPDATE notification_events
                            SET title = ?, summary = ?, preview = ?, target_id = ?,
                                expires_at = ?, expires_epoch = ?, revision = revision + 1
                            WHERE event_id = ?
                            """,
                            (*values, existing["event_id"]),
                        )
                        existing = connection.execute(
                            "SELECT * FROM notification_events WHERE event_id = ?",
                            (existing["event_id"],),
                        ).fetchone()
                return dict(existing), False
            connection.execute(
                """
                INSERT INTO notification_events(
                    event_id, source, source_event_id, subject, event_type, title,
                    summary, preview, target_id, display_at, expires_at, expires_epoch,
                    deliver_epoch, delivered_at, revision, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, 1, ?)
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
                    event.get("target_id", ""),
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

    def queue_delivery(self, event_id: str, device_id: str, deliver_epoch: int) -> None:
        with self._connect() as connection:
            connection.execute(
                """
                INSERT OR IGNORE INTO event_delivery_queue(
                    event_id, device_id, deliver_epoch, created_at
                ) VALUES (?, ?, ?, ?)
                """,
                (event_id, device_id, deliver_epoch, int(time.time())),
            )

    def due_delivery_queue(
        self, *, event_id: str | None = None, limit: int = 100
    ) -> list[dict[str, Any]]:
        now = int(time.time())
        query = """
            SELECT q.event_id, q.device_id
            FROM event_delivery_queue q
            JOIN notification_events e ON e.event_id = q.event_id
            WHERE q.deliver_epoch <= ?
              AND (e.expires_epoch IS NULL OR e.expires_epoch > ?)
        """
        parameters: list[Any] = [now, now]
        if event_id is not None:
            query += " AND q.event_id = ?"
            parameters.append(event_id)
        query += " ORDER BY q.deliver_epoch ASC, q.event_id, q.device_id LIMIT ?"
        parameters.append(limit)
        with self._connect() as connection:
            rows = connection.execute(query, parameters).fetchall()
        return [dict(row) for row in rows]

    def get_event(self, event_id: str) -> dict[str, Any] | None:
        with self._connect() as connection:
            row = connection.execute(
                "SELECT * FROM notification_events WHERE event_id = ?", (event_id,)
            ).fetchone()
        return dict(row) if row is not None else None

    def finish_queued_delivery(self, event_id: str, device_id: str, *, delivered: bool) -> None:
        with self._connect() as connection:
            connection.execute("BEGIN IMMEDIATE")
            if delivered:
                connection.execute(
                    """
                    INSERT OR IGNORE INTO event_deliveries(event_id, device_id, delivered_at)
                    VALUES (?, ?, ?)
                    """,
                    (event_id, device_id, int(time.time())),
                )
            connection.execute(
                "DELETE FROM event_delivery_queue WHERE event_id = ? AND device_id = ?",
                (event_id, device_id),
            )
            pending = connection.execute(
                "SELECT 1 FROM event_delivery_queue WHERE event_id = ? LIMIT 1",
                (event_id,),
            ).fetchone()
            if pending is None:
                connection.execute(
                    "UPDATE notification_events SET delivered_at = ? WHERE event_id = ? AND delivered_at IS NULL",
                    (int(time.time()), event_id),
                )

    def finish_event_without_targets(self, event_id: str) -> None:
        with self._connect() as connection:
            queued = connection.execute(
                "SELECT 1 FROM event_delivery_queue WHERE event_id = ? LIMIT 1",
                (event_id,),
            ).fetchone()
            if queued is None:
                connection.execute(
                    "UPDATE notification_events SET delivered_at = ? WHERE event_id = ? AND delivered_at IS NULL",
                    (int(time.time()), event_id),
                )

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

    def create_auth_request(
        self,
        *,
        subject: str,
        application: str,
        domain: str,
        display_username: str,
        source_ip: str,
        ttl: int,
    ) -> dict[str, Any]:
        now = int(time.time())
        request_id = secrets.token_urlsafe(24)
        with self._connect() as connection:
            connection.execute(
                "UPDATE auth_requests SET status = 'expired', completed_at = ? "
                "WHERE status = 'pending' AND expires_at <= ?",
                (now, now),
            )
            connection.execute(
                """
                INSERT INTO auth_requests(
                    request_id, subject, application, domain, display_username,
                    source_ip, status, created_at, expires_at
                ) VALUES (?, ?, ?, ?, ?, ?, 'pending', ?, ?)
                """,
                (
                    request_id,
                    subject,
                    application,
                    domain,
                    display_username,
                    source_ip,
                    now,
                    now + ttl,
                ),
            )
            row = connection.execute(
                "SELECT * FROM auth_requests WHERE request_id = ?", (request_id,)
            ).fetchone()
        return dict(row)

    def pending_auth_request_for_device(self, device_id: str) -> dict[str, Any] | None:
        now = int(time.time())
        with self._connect() as connection:
            connection.execute(
                "UPDATE auth_requests SET status = 'expired', completed_at = ? "
                "WHERE status = 'pending' AND expires_at <= ?",
                (now, now),
            )
            row = connection.execute(
                """
                SELECT a.* FROM auth_requests a
                JOIN push_registrations r ON r.subject = a.subject
                JOIN users u ON u.subject = a.subject
                WHERE r.device_id = ?
                  AND r.mode = 'personal'
                  AND u.active = 1
                  AND a.status = 'pending'
                  AND a.expires_at > ?
                ORDER BY a.created_at DESC
                LIMIT 1
                """,
                (device_id, now),
            ).fetchone()
        return dict(row) if row is not None else None

    def decide_auth_request(self, request_id: str, device_id: str, approved: bool) -> bool:
        now = int(time.time())
        status = "approved" if approved else "denied"
        with self._connect() as connection:
            cursor = connection.execute(
                """
                UPDATE auth_requests
                SET status = ?, approving_device_id = ?, completed_at = ?
                WHERE request_id = ?
                  AND status = 'pending'
                  AND expires_at > ?
                  AND subject = (
                      SELECT subject FROM push_registrations
                      WHERE device_id = ? AND mode = 'personal'
                  )
                """,
                (status, device_id, now, request_id, now, device_id),
            )
        return cursor.rowcount == 1

    def auth_request_status(self, request_id: str) -> str | None:
        now = int(time.time())
        with self._connect() as connection:
            connection.execute(
                "UPDATE auth_requests SET status = 'expired', completed_at = ? "
                "WHERE request_id = ? AND status = 'pending' AND expires_at <= ?",
                (now, request_id, now),
            )
            row = connection.execute(
                "SELECT status FROM auth_requests WHERE request_id = ?", (request_id,)
            ).fetchone()
        return str(row["status"]) if row is not None else None

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
