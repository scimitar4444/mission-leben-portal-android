from __future__ import annotations

import json
import tempfile
import time
import unittest
from pathlib import Path
from types import SimpleNamespace

from cryptography.hazmat.primitives.asymmetric import ec

from mission_leben_bridge.communication_directory import CommunicationDirectory
from mission_leben_bridge.http_api import BridgeRequestHandler
from mission_leben_bridge.security import SecretBox, base64url_encode, device_key_id, sign_source_request
from mission_leben_bridge.service import ApiError, BridgeService, supports_helpdesk_push
from mission_leben_bridge.store import Store
from test_service import FakeAuthentik, FakeNtfy


class HelpdeskPushTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.path = Path(self.temp.name) / "assignments.json"
        self.assignment()
        self.store = Store(Path(self.temp.name) / "bridge.sqlite3", b"h" * 32, SecretBox(b"d" * 32))
        self.ntfy = FakeNtfy()
        self.service = BridgeService(self.store, FakeAuthentik(), self.ntfy, (),
            communication_directory=CommunicationDirectory(self.path))
        numbers = ec.generate_private_key(ec.SECP256R1()).public_key().public_numbers()
        jwk = {"kty": "EC", "crv": "P-256", "x": base64url_encode(numbers.x.to_bytes(32, "big")),
            "y": base64url_encode(numbers.y.to_bytes(32, "big"))}
        jwk["kid"] = device_key_id(jwk)
        self.registration = {"provider": "ntfy", "authentik_device_token": "valid-agent-token",
            "mode": "personal", "notification_privacy": "standard", "app_version": "0.22.1-local",
            "key_id": jwk["kid"], "public_key_jwk": jwk}
        self.device = "11111111-1111-1111-1111-111111111111"
        self.service.register_push(self.device, "valid-token", self.registration)
        self.payload = {"recipient_login": "user@example.invalid", "ticket_id": "42", "article_id": "84",
            "ticket_number": "2026101201", "title": "Drucker", "preview": "Bitte neu starten"}

    def tearDown(self):
        self.temp.cleanup()

    def assignment(self, *, allowed=True, age=0, extra=()):
        self.path.write_text(json.dumps({"generated_at": int(time.time()) - age, "assignments": [
            {"subject": "authentik-user-1", "helpdesk": allowed, "helpdesk_login": "user@example.invalid"}, *extra
        ]}))

    def test_reply_is_deduplicated_metadata_only_and_has_validated_target(self):
        result = self.service.ingest_event("otobo", self.payload)
        self.assertEqual(1, result["dispatched"])
        data = self.ntfy.messages[0][2]
        self.assertEqual({"action", "event_id", "event_type", "revision"}, set(data))
        self.assertEqual("open_helpdesk", data["event_type"])
        event = self.store.get_event(result["event_id"])
        self.assertEqual("42:84", event["target_id"])
        self.assertEqual("authentik-user-1", event["subject"])
        self.assertFalse(self.service.ingest_event("otobo", self.payload)["created"])
        self.assertEqual(1, len(self.ntfy.messages))

    def test_unapproved_unknown_or_inactive_recipient_never_gets_push(self):
        self.assignment(allowed=False)
        self.assertTrue(self.service.ingest_event("otobo", self.payload)["ignored"])
        self.assignment()
        self.assertTrue(self.service.ingest_event("otobo", {**self.payload, "recipient_login": "other@example.invalid"})["ignored"])
        self.service.set_user_active("authentik-user-1", False)
        with self.assertRaises(ApiError):
            self.service.ingest_event("otobo", self.payload)
        self.assertEqual(["refresh_security_state"], [message[2]["action"] for message in self.ntfy.messages])

    def test_stale_and_ambiguous_assignment_fail_closed(self):
        self.assignment(age=901)
        with self.assertRaises(ApiError) as caught:
            self.service.ingest_event("otobo", self.payload)
        self.assertEqual(503, caught.exception.status)
        self.assignment(extra=[{"subject": "other", "helpdesk": True, "helpdesk_login": "USER@example.invalid"}])
        with self.assertRaises(ApiError):
            self.service.ingest_event("otobo", self.payload)
        self.assertEqual([], self.ntfy.messages)

    def test_source_cannot_select_subject_action_or_arbitrary_url(self):
        for change in ({"user_subject": "other"}, {"event_type": "open_mail"}, {"ticket_id": "https://evil.invalid"},
            {"article_id": "0"}, {"ticket_id": True}):
            with self.assertRaises(ApiError):
                self.service.ingest_event("otobo", {**self.payload, **change})
        with self.assertRaises(ApiError):
            self.service.ingest_event("test", {"event_type": "open_helpdesk"})

    def test_old_app_waits_for_upgrade_and_replays_only_once(self):
        self.service.register_push(self.device, "valid-token", {**self.registration, "app_version": "0.22.0"})
        result = self.service.ingest_event("otobo", self.payload)
        self.assertEqual(0, result["dispatched"])
        self.service.register_push(self.device, "valid-token", self.registration)
        self.assertEqual(1, self.service.dispatch_due_events())
        self.service.register_push(self.device, "valid-token", self.registration)
        self.assertEqual(0, self.service.dispatch_due_events())
        for invalid in ("", "invalid", "0.22.0"):
            self.assertFalse(supports_helpdesk_push(invalid))

    def test_access_removed_before_dispatch_blocks_pending_reply(self):
        self.ntfy.configured = False
        result = self.service.ingest_event("otobo", self.payload)
        self.assignment(allowed=False)
        self.ntfy.configured = True
        self.assertEqual(0, self.service.dispatch_due_events(event_id=result["event_id"]))
        self.assertEqual([], self.ntfy.messages)

    def test_communication_off_and_quiet_hours_apply(self):
        self.service.register_push(self.device, "valid-token", {**self.registration,
            "communication_notifications_enabled": False})
        self.assertEqual(0, self.service.ingest_event("otobo", self.payload)["dispatched"])
        self.service.register_push(self.device, "valid-token", {**self.registration,
            "quiet_hours_enabled": True, "quiet_start_minutes": 0, "quiet_end_minutes": 1439})
        # Fixed server time inside the configured quiet period.
        from unittest.mock import patch
        with patch.object(self.service, "communication_allowed", return_value=False):
            self.assertEqual(0, self.service.ingest_event("otobo", {**self.payload, "article_id": "85"})["dispatched"])

    def test_source_key_is_separate_and_cannot_offboard_or_set_calendar(self):
        timestamp = str(int(time.time()))
        body = b'{}'
        secret = b"o" * 32
        handler = SimpleNamespace(path="/sources/otobo", server=SimpleNamespace(settings=SimpleNamespace(
            otobo_hmac_secret=secret, projectsend_hmac_secret=b"p" * 32, internal_hmac_secret=b"i" * 32)),
            headers={"X-ML-Source": "otobo", "X-ML-Timestamp": timestamp,
                "X-ML-Signature": sign_source_request(secret, "otobo", timestamp, body)})
        self.assertEqual("otobo", BridgeRequestHandler._verify_internal(handler, body))
        for path in ("/internal/v1/calendar/snapshot", "/internal/v1/users/victim/status"):
            handler.path = path
            with self.assertRaises(ApiError):
                BridgeRequestHandler._verify_internal(handler, body)
        handler.path = "/sources/otobo"
        handler.headers["X-ML-Signature"] = sign_source_request(b"i" * 32, "otobo", timestamp, body)
        with self.assertRaises(ApiError):
            BridgeRequestHandler._verify_internal(handler, body)
