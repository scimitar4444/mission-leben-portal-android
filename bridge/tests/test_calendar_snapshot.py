from __future__ import annotations

import json
import sqlite3
import tempfile
import time
import unittest
from contextlib import closing
from pathlib import Path
from unittest.mock import patch

from mission_leben_bridge.communication_directory import CommunicationDirectory
from mission_leben_bridge.security import SecretBox
from mission_leben_bridge.service import ApiError, BridgeService
from mission_leben_bridge.store import Store
from mission_leben_bridge.zimbra_waitset import ZimbraAppointment, ZimbraAppointments
from mission_leben_bridge.zimbra_worker import ZimbraWorker
from test_service import FakeAuthentik, FakeNtfy


SUBJECT = "authentik-user-1"


class CalendarSnapshotTest(unittest.TestCase):
    def setUp(self) -> None:
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        directory = Path(temporary.name)
        self.assignments = directory / "assignments.json"
        self.set_assignment(True)
        self.store = Store(directory / "bridge.sqlite3", b"h" * 32, SecretBox(b"d" * 32))
        self.store.upsert_user(SUBJECT, "user@example.invalid", "Test User")
        self.service = BridgeService(
            self.store, FakeAuthentik(), FakeNtfy(), (),
            communication_directory=CommunicationDirectory(self.assignments),
        )
        self.event = {
            "id": "a" * 32,
            "title": "Dienstbesprechung",
            "location": "Raum 1",
            "start_millis": 1_800_000_000_000,
            "end_millis": 1_800_003_600_000,
            "all_day": False,
        }

    def set_assignment(self, allowed: bool) -> None:
        self.assignments.write_text(json.dumps({"assignments": [{
            "subject": SUBJECT, "zimbra": True, "personal_calendar": allowed,
        }]}), encoding="utf-8")

    def request(self, method: str, mode: str = "personal", version: str = "0.21.6") -> dict:
        with patch.object(self.service, "_verify_device_request", return_value={"subject": SUBJECT, "mode": mode, "app_version": version}):
            return getattr(self.service, method)(
                device_id="device", key_id="key", timestamp="0", nonce="nonce",
                signature="signature", path="/v1/calendar/" + method.removeprefix("calendar_"),
            )

    def test_personal_snapshot_updates_and_removes_events(self) -> None:
        self.service.put_calendar_snapshot("zimbra", {"user_subject": SUBJECT, "events": [self.event]})
        self.assertEqual({"available": True}, self.request("calendar_access"))
        self.assertEqual([self.event], self.request("calendar_snapshot")["events"])
        self.service.put_calendar_snapshot("zimbra", {"user_subject": SUBJECT, "events": []})
        self.assertEqual([], self.request("calendar_snapshot")["events"])

    def test_shared_and_unassigned_devices_are_denied(self) -> None:
        self.service.put_calendar_snapshot("zimbra", {"user_subject": SUBJECT, "events": [self.event]})
        self.assertEqual({"available": False}, self.request("calendar_access", mode="shared"))
        with self.assertRaises(ApiError) as shared:
            self.request("calendar_snapshot", mode="shared")
        self.assertEqual(403, shared.exception.status)
        self.set_assignment(False)
        self.assertEqual({"available": False}, self.request("calendar_access"))
        with self.assertRaises(ApiError) as unassigned:
            self.service.put_calendar_snapshot("zimbra", {"user_subject": SUBJECT, "events": [self.event]})
        self.assertEqual(403, unassigned.exception.status)

    def test_new_personal_assignment_is_available_without_a_pilot_list(self) -> None:
        self.set_assignment(False)
        self.assertEqual({"available": False}, self.request("calendar_access"))
        self.set_assignment(True)
        self.assertEqual({"available": True}, self.request("calendar_access"))

    def test_missing_assignment_file_fails_closed(self) -> None:
        self.assignments.unlink()
        with self.assertRaises(ApiError) as unavailable:
            self.request("calendar_access")
        self.assertEqual(503, unavailable.exception.status)
        with self.assertRaises(ApiError) as rejected:
            self.service.put_calendar_snapshot("zimbra", {"user_subject": SUBJECT, "events": []})
        self.assertEqual(503, rejected.exception.status)

    def test_source_and_incomplete_snapshots_fail_closed(self) -> None:
        with self.assertRaises(ApiError):
            self.service.put_calendar_snapshot("talk", {"user_subject": SUBJECT, "events": [self.event]})
        with self.assertRaises(ApiError):
            self.service.put_calendar_snapshot("zimbra", {"user_subject": SUBJECT, "events": [self.event, self.event]})
        self.assertIsNone(self.store.get_calendar_snapshot(SUBJECT))

    def test_stale_snapshot_and_offboarding(self) -> None:
        self.store.put_calendar_snapshot(SUBJECT, [self.event], int(time.time()) - 8000)
        with self.assertRaises(ApiError) as stale:
            self.request("calendar_snapshot")
        self.assertEqual(503, stale.exception.status)
        self.store.offboard_subject(SUBJECT)
        self.assertIsNone(self.store.get_calendar_snapshot(SUBJECT))

    def test_two_hundred_events_are_accepted_but_overflow_keeps_previous_snapshot(self) -> None:
        events = [{**self.event, "id": f"event-{index:026d}"} for index in range(200)]
        self.service.put_calendar_snapshot("zimbra", {"user_subject": SUBJECT, "events": events})
        self.assertEqual(200, len(self.request("calendar_snapshot")["events"]))
        with self.assertRaises(ApiError) as overflow:
            self.service.put_calendar_snapshot("zimbra", {
                "user_subject": SUBJECT, "events": events + [{**self.event, "id": "z" * 32}],
            })
        self.assertEqual(400, overflow.exception.status)
        self.assertEqual(events, self.request("calendar_snapshot")["events"])

    def test_legacy_app_never_receives_a_truncated_large_calendar(self) -> None:
        events = [{**self.event, "id": f"event-{index:026d}"} for index in range(105)]
        self.service.put_calendar_snapshot("zimbra", {"user_subject": SUBJECT, "events": events})
        for version in ("0.21.5", "0.20.0", "", "invalid"):
            with self.subTest(version=version), self.assertRaises(ApiError) as older:
                self.request("calendar_snapshot", version=version)
            self.assertEqual(503, older.exception.status)
        for version in ("0.21.6", "0.22.0", "1.0.0"):
            self.assertEqual(105, len(self.request("calendar_snapshot", version=version)["events"]))
        self.service.put_calendar_snapshot("zimbra", {"user_subject": SUBJECT, "events": events[:100]})
        self.assertEqual(100, len(self.request("calendar_snapshot", version="0.21.5")["events"]))

    def test_calendar_subject_and_location_are_encrypted_at_rest(self) -> None:
        self.store.put_calendar_snapshot(SUBJECT, [self.event], int(time.time()))
        with closing(sqlite3.connect(self.store.path)) as connection:
            ciphertext = connection.execute(
                "SELECT payload_ciphertext FROM calendar_snapshots WHERE subject = ?", (SUBJECT,),
            ).fetchone()[0]
        self.assertNotIn("Dienstbesprechung", ciphertext)
        self.assertNotIn("Raum 1", ciphertext)
        self.assertEqual([self.event], self.store.get_calendar_snapshot(SUBJECT)[0])


class FakeCalendarSoap:
    def __init__(self, events: list[ZimbraAppointment]):
        self.events = events

    def upcoming_appointments(self, account_id: str) -> list[ZimbraAppointment]:
        return self.events


class FakeCalendarBridge:
    def __init__(self):
        self.snapshots: list[dict] = []

    def publish(self, payload: dict) -> None:
        pass

    def publish_calendar_snapshot(self, payload: dict) -> None:
        self.snapshots.append(payload)


class WorkerCalendarSnapshotTest(unittest.TestCase):
    def test_worker_does_not_snapshot_unassigned_account(self) -> None:
        event = ZimbraAppointment("123", 1_800_000_000_000, 3_600_000, "Meeting", "Raum")
        bridge = FakeCalendarBridge()
        worker = ZimbraWorker(FakeCalendarSoap([event]), bridge, {"account": {"subject": SUBJECT}})
        worker._scan_calendar("account")
        self.assertEqual([], bridge.snapshots)

    def test_worker_publishes_complete_replacement_and_empty_snapshot(self) -> None:
        soap = FakeCalendarSoap([ZimbraAppointment("123", 1_800_000_000_000, 3_600_000, "Meeting", "Raum")])
        bridge = FakeCalendarBridge()
        worker = ZimbraWorker(soap, bridge, {"account": {"subject": SUBJECT, "email": "user@example.invalid", "personal_calendar": True}})
        worker._scan_calendar("account")
        self.assertEqual("Meeting", bridge.snapshots[0]["events"][0]["title"])
        soap.events = []
        worker._scan_calendar("account")
        self.assertEqual([], bridge.snapshots[1]["events"])

    def test_result_limit_never_replaces_the_snapshot(self) -> None:
        event = ZimbraAppointment("123", 1_800_000_000_000, 3_600_000, "Meeting", "Raum")
        bridge = FakeCalendarBridge()
        worker = ZimbraWorker(FakeCalendarSoap([event] * 201), bridge, {"account": {"subject": SUBJECT, "personal_calendar": True}})
        worker._scan_calendar("account")
        self.assertEqual([], bridge.snapshots)

    def test_complete_two_hundred_instances_are_published(self) -> None:
        events = [ZimbraAppointment(str(index), 1_800_000_000_000, 3_600_000, "Meeting", "") for index in range(200)]
        bridge = FakeCalendarBridge()
        worker = ZimbraWorker(FakeCalendarSoap(ZimbraAppointments(events, complete=True)), bridge,
                              {"account": {"subject": SUBJECT, "personal_calendar": True}})
        worker._scan_calendar("account")
        self.assertEqual(200, len(bridge.snapshots[0]["events"]))

    def test_incomplete_short_result_never_replaces_calendar_with_empty_list(self) -> None:
        bridge = FakeCalendarBridge()
        worker = ZimbraWorker(FakeCalendarSoap(ZimbraAppointments([], complete=False)), bridge,
                              {"account": {"subject": SUBJECT, "personal_calendar": True}})
        worker._scan_calendar("account")
        self.assertEqual([], bridge.snapshots)
