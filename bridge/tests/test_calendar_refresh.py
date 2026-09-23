from __future__ import annotations

import tempfile
import time
import unittest
from datetime import datetime, timezone
from pathlib import Path
from unittest.mock import patch

from mission_leben_bridge.security import SecretBox
from mission_leben_bridge.service import BridgeService
from mission_leben_bridge.store import Store
from test_service import FakeAuthentik, FakeNtfy


class CalendarRefreshTest(unittest.TestCase):
    def setUp(self) -> None:
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.store = Store(Path(self.directory.name) / "bridge.sqlite3", b"h" * 32, SecretBox(b"d" * 32))
        self.ntfy = FakeNtfy()
        self.service = BridgeService(self.store, FakeAuthentik(), self.ntfy, ())
        self.now = int(time.time())
        self.device_id = "11111111-1111-1111-1111-111111111111"
        self.registration = {
            "device_id": self.device_id,
            "subject": "authentik-user-1",
            "calendar_reminder_minutes": 30,
            "agent_token": "valid-agent-token",
            "push_provider": "ntfy",
            "ntfy_topic": "test-topic",
            "publish_token": "test-token",
            "communication_enabled": True,
        }
        # The tested queue has a registration foreign key; provision one without
        # credentials/network operations. The dispatcher receives a safe fake.
        self.store.upsert_user("authentik-user-1", "user@example.invalid", "Test User")
        self.store.register_push(
            device_id=self.device_id, subject="authentik-user-1",
            subscribe_token="subscribe", publish_token="publish", topic="test-topic",
            reader_username="reader", writer_username="writer", agent_token="valid-agent-token",
            key_id="test-key", public_jwk={}, mode="personal", privacy="standard",
            app_version="test", calendar_reminder_minutes=30,
        )
        self.payload = {
            "source_event_id": "calendar:account:appointment:instance",
            "user_subject": "authentik-user-1",
            "event_type": "open_calendar",
            "title": "Termin",
            "summary": "09:00 Uhr",
            "display_at": self.iso(self.now + 3600),
            "expires_at": self.iso(self.now + 7200),
        }

    @staticmethod
    def iso(epoch: int) -> str:
        return datetime.fromtimestamp(epoch, timezone.utc).isoformat().replace("+00:00", "Z")

    def queue(self) -> list[tuple]:
        with self.store._connect() as connection:
            return [tuple(row) for row in connection.execute("SELECT * FROM event_delivery_queue")]

    def test_refresh_preserves_event_and_personal_schedule_and_dispatches_once(self) -> None:
        initial = self.service.ingest_event("zimbra", self.payload)
        queue = self.queue()
        self.assertEqual(1, len(queue))
        self.assertEqual(self.now + 1800, queue[0][2])
        updated = self.service.ingest_event("zimbra", {
            **self.payload, "title": "Team meeting", "summary": "09:00 Uhr · Room 2",
            "expires_at": self.iso(self.now + 9000),
        })
        self.assertEqual(initial["event_id"], updated["event_id"])
        self.assertFalse(updated["created"])
        self.assertEqual(0, updated["dispatched"])
        self.assertEqual(queue, self.queue())
        event = self.store.get_event(initial["event_id"])
        self.assertEqual("Team meeting", event["title"])
        self.assertEqual("09:00 Uhr · Room 2", event["summary"])
        self.assertEqual(self.now + 9000, event["expires_epoch"])
        self.assertEqual(2, event["revision"])
        self.assertIsNone(event["delivered_at"])
        self.assertEqual([], self.ntfy.messages)
        with patch("time.time", return_value=self.now + 1801), patch.object(
            self.store, "get_registration", return_value=self.registration
        ):
            self.assertEqual(1, self.service.dispatch_due_events())
            self.assertEqual(0, self.service.dispatch_due_events())
        self.assertEqual("2", self.ntfy.messages[0][2]["revision"])
        self.assertEqual([], self.queue())

    def test_identical_calendar_refresh_does_not_increase_revision(self) -> None:
        initial = self.service.ingest_event("zimbra", self.payload)
        self.service.ingest_event("zimbra", self.payload)
        self.assertEqual(1, self.store.get_event(initial["event_id"])["revision"])

    def test_already_delivered_calendar_is_not_requeued(self) -> None:
        initial = self.service.ingest_event("zimbra", self.payload)
        self.store.finish_queued_delivery(initial["event_id"], self.device_id, delivered=True)
        before = self.store.get_event(initial["event_id"])
        self.service.ingest_event("zimbra", {**self.payload, "title": "Updated title"})
        after = self.store.get_event(initial["event_id"])
        self.assertEqual("Updated title", after["title"])
        self.assertEqual(before["delivered_at"], after["delivered_at"])
        self.assertTrue(self.store.delivery_exists(initial["event_id"], self.device_id))
        self.assertEqual([], self.queue())
        self.assertEqual(0, self.service.dispatch_due_events())
        self.assertEqual([], self.ntfy.messages)

    def test_refresh_cannot_reassign_recipient_event_type_or_start(self) -> None:
        initial = self.service.ingest_event("zimbra", self.payload)
        before = self.store.get_event(initial["event_id"])
        for change in (
            {"user_subject": "other-user"},
            {"event_type": "open_mail"},
            {"display_at": self.iso(self.now + 4000)},
        ):
            with self.subTest(change=change):
                self.service.ingest_event("zimbra", {**self.payload, **change, "title": "Other title"})
                self.assertEqual(before, self.store.get_event(initial["event_id"]))

    def test_mail_and_talk_duplicates_remain_immutable(self) -> None:
        for event_type in ("open_mail", "open_talk"):
            with self.subTest(event_type=event_type):
                payload = {**self.payload, "source_event_id": event_type, "event_type": event_type}
                initial = self.service.ingest_event("zimbra", payload)
                before = self.store.get_event(initial["event_id"])
                self.service.ingest_event("zimbra", {**payload, "title": "Changed"})
                self.assertEqual(before, self.store.get_event(initial["event_id"]))
