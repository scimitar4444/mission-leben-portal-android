from __future__ import annotations

import sqlite3
import tempfile
import unittest
from pathlib import Path

from mission_leben_bridge.security import SecretBox
from mission_leben_bridge.store import Store


class DocumentEventMigrationTest(unittest.TestCase):
    def test_old_events_and_delivery_foreign_keys_survive_upgrade(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "bridge.sqlite3"
            with sqlite3.connect(path) as connection:
                connection.executescript("""
                    CREATE TABLE notification_events (
                        event_id TEXT PRIMARY KEY, source TEXT NOT NULL,
                        source_event_id TEXT NOT NULL, subject TEXT NOT NULL,
                        event_type TEXT NOT NULL CHECK (event_type IN (
                            'open_mail', 'open_calendar', 'open_talk')),
                        title TEXT NOT NULL, summary TEXT NOT NULL, preview TEXT NOT NULL,
                        target_id TEXT NOT NULL DEFAULT '', display_at TEXT,
                        expires_at TEXT, expires_epoch INTEGER,
                        deliver_epoch INTEGER NOT NULL, delivered_at INTEGER,
                        revision INTEGER NOT NULL, created_at INTEGER NOT NULL,
                        UNIQUE(source, source_event_id)
                    );
                    CREATE TABLE event_deliveries (
                        event_id TEXT NOT NULL REFERENCES notification_events(event_id) ON DELETE CASCADE,
                        device_id TEXT NOT NULL, delivered_at INTEGER NOT NULL,
                        PRIMARY KEY(event_id, device_id)
                    );
                    INSERT INTO notification_events VALUES (
                        'old-event', 'zimbra', 'mail:42', 'person', 'open_mail',
                        'Sender', 'Subject', '', '', NULL, NULL, NULL, 1, 1, 1, 1
                    );
                    INSERT INTO event_deliveries VALUES ('old-event', 'device', 1);
                    CREATE TABLE calendar_snapshots (
                        subject TEXT PRIMARY KEY, payload_ciphertext TEXT NOT NULL,
                        fetched_at INTEGER NOT NULL
                    );
                    INSERT INTO calendar_snapshots VALUES ('person', 'encrypted', 42);
                """)
            store = Store(path, b"h" * 32, SecretBox(b"d" * 32))
            self.assertEqual("open_mail", store.get_event("old-event")["event_type"])
            with sqlite3.connect(path) as connection:
                self.assertEqual(1, connection.execute(
                    "SELECT COUNT(*) FROM event_deliveries WHERE event_id='old-event'"
                ).fetchone()[0])
                self.assertEqual(('encrypted', 42), connection.execute(
                    "SELECT payload_ciphertext, fetched_at FROM calendar_snapshots WHERE subject='person'"
                ).fetchone())
                self.assertEqual([], connection.execute("PRAGMA foreign_key_check").fetchall())
            event, created = store.put_event({
                "source": "projectsend", "source_event_id": "notification:1",
                "subject": "a" * 64, "event_type": "open_documents",
                "title": "ML Dokumente", "summary": "Neues Dokument", "preview": "",
                "target_id": "", "display_at": None, "expires_at": None,
                "expires_epoch": None, "deliver_epoch": 1,
            })
            self.assertTrue(created)
            self.assertEqual("open_documents", event["event_type"])
            Store(path, b"h" * 32, SecretBox(b"d" * 32))
            self.assertEqual("open_mail", store.get_event("old-event")["event_type"])


if __name__ == "__main__":
    unittest.main()
