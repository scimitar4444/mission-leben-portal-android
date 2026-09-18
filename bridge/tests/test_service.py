from __future__ import annotations

import tempfile
import time
import unittest
import hashlib
import hmac
import json
from datetime import datetime, timedelta, timezone
from pathlib import Path

from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric import ec

from mission_leben_bridge.authentik import UserInfo
from mission_leben_bridge.security import (
    SecretBox,
    base64url_encode,
    canonical_device_request,
    device_key_id,
)
from mission_leben_bridge.nextcloud_talk import NextcloudTalkWebhook
from mission_leben_bridge.service import BridgeService
from mission_leben_bridge.store import Store


class FakeAuthentik:
    def user_info(self, access_token: str) -> UserInfo:
        if access_token != "valid-token":
            raise AssertionError("unexpected test token")
        return UserInfo("authentik-user-1", "user@example.invalid", "Test User")


class FakeFcm:
    configured = True

    def __init__(self) -> None:
        self.messages: list[tuple[str, dict[str, str]]] = []

    def send(self, installation_id: str, data: dict[str, str]) -> None:
        self.messages.append((installation_id, data))


class ServiceTest(unittest.TestCase):
    def setUp(self) -> None:
        self.directory = tempfile.TemporaryDirectory()
        self.store = Store(
            Path(self.directory.name) / "bridge.sqlite3",
            b"h" * 32,
            SecretBox(b"d" * 32),
        )
        self.fcm = FakeFcm()
        self.service = BridgeService(self.store, FakeAuthentik(), self.fcm, ())

    def tearDown(self) -> None:
        self.directory.cleanup()

    def test_enrollment_registration_and_minimal_fcm_payload(self) -> None:
        private_key = ec.generate_private_key(ec.SECP256R1())
        numbers = private_key.public_key().public_numbers()
        jwk = {
            "kty": "EC",
            "crv": "P-256",
            "x": base64url_encode(numbers.x.to_bytes(32, "big")),
            "y": base64url_encode(numbers.y.to_bytes(32, "big")),
        }
        jwk["kid"] = device_key_id(jwk)
        token = self.store.create_enrollment_token("personal", 900, auto_trust=True)
        enrollment = self.service.enroll(
            {
                "enrollment_token": token,
                "mode": "personal",
                "device_name": "Test Phone",
                "platform": "android",
                "os_version": "13",
                "app_version": "0.4.0",
                "key_id": jwk["kid"],
                "public_key_jwk": jwk,
            }
        )
        self.service.register_push(
            enrollment["device_id"],
            "valid-token",
            {
                "provider": "fcm",
                "installation_id": "installation-secret",
                "notification_privacy": "standard",
                "app_version": "0.4.0",
            },
        )
        result = self.service.ingest_event(
            "zimbra",
            {
                "source_event_id": "mail:account:42",
                "user_subject": "authentik-user-1",
                "event_type": "open_mail",
                "title": "Sender Name",
                "summary": "Confidential subject",
                "preview": "Confidential preview",
            },
        )

        self.assertTrue(result["created"])
        self.assertEqual(1, result["dispatched"])
        self.assertEqual("installation-secret", self.fcm.messages[0][0])
        self.assertEqual(
            {"action", "event_id", "event_type", "revision"},
            set(self.fcm.messages[0][1]),
        )
        self.assertNotIn("Confidential", str(self.fcm.messages[0][1]))

        status_path = f"/v1/devices/{enrollment['device_id']}/status"
        timestamp = str(int(time.time()))
        nonce = "status-nonce-0123456789"
        canonical = canonical_device_request(
            "GET", status_path, enrollment["device_id"], jwk["kid"], timestamp, nonce
        )
        signature = base64url_encode(private_key.sign(canonical, ec.ECDSA(hashes.SHA256())))
        self.assertEqual(
            "trusted",
            self.service.device_status(
                enrollment["device_id"], jwk["kid"], timestamp, nonce, signature, status_path
            )["status"],
        )

        detail_path = f"/v1/notifications/{result['event_id']}"
        detail_nonce = "detail-nonce-0123456789"
        detail_canonical = canonical_device_request(
            "GET", detail_path, enrollment["device_id"], jwk["kid"], timestamp, detail_nonce
        )
        detail_signature = base64url_encode(
            private_key.sign(detail_canonical, ec.ECDSA(hashes.SHA256()))
        )
        detail = self.service.notification_detail(
            result["event_id"],
            enrollment["device_id"],
            jwk["kid"],
            timestamp,
            detail_nonce,
            detail_signature,
            detail_path,
        )
        self.assertEqual("Sender Name", detail["title"])
        self.assertEqual("Confidential subject", detail["summary"])
        self.assertEqual("", detail["preview"])

    def test_future_calendar_event_waits_for_dispatch_time(self) -> None:
        future = datetime.now(timezone.utc) + timedelta(hours=1)
        result = self.service.ingest_event(
            "zimbra",
            {
                "source_event_id": "calendar:account:99:instance",
                "user_subject": "authentik-user-1",
                "event_type": "open_calendar",
                "title": "Team meeting",
                "summary": "Tomorrow, 10:00",
                "deliver_at": future.isoformat(),
                "expires_at": (future + timedelta(hours=2)).isoformat(),
            },
        )
        self.assertEqual(0, result["dispatched"])
        self.assertEqual([], self.fcm.messages)
        self.assertEqual(0, self.service.dispatch_due_events())

    def test_talk_bot_signature_and_recipient_mapping(self) -> None:
        secret = b"talk-bot-secret"
        adapter = NextcloudTalkWebhook(
            self.service,
            self.store,
            secret,
            "https://cloud.example.invalid",
            {"room1": ("authentik-user-1", "authentik-user-2")},
            {"alice": "authentik-user-1"},
        )
        body = json.dumps(
            {
                "type": "Create",
                "actor": {"type": "Person", "id": "users/alice", "name": "Alice"},
                "object": {
                    "type": "Note",
                    "id": "42",
                    "content": json.dumps({"message": "Hallo {mention}", "parameters": {"mention": {"name": "Team"}}}),
                },
                "target": {"type": "Collection", "id": "room1", "name": "Team IT"},
            },
            separators=(",", ":"),
        ).encode()
        random_value = "A" * 64
        signature = hmac.new(secret, random_value.encode() + body, hashlib.sha256).hexdigest()

        result = adapter.receive(
            body,
            random_value,
            signature,
            "https://cloud.example.invalid",
        )

        self.assertEqual(1, result["events"])


if __name__ == "__main__":
    unittest.main()
