from __future__ import annotations

import tempfile
import time
import unittest
import hashlib
import hmac
import json
import threading
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

    def device_id(self, agent_token: str) -> str:
        if agent_token != "valid-agent-token":
            raise AssertionError("unexpected agent token")
        return "11111111-1111-1111-1111-111111111111"


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

    def test_authentik_device_registration_and_minimal_fcm_payload(self) -> None:
        private_key = ec.generate_private_key(ec.SECP256R1())
        numbers = private_key.public_key().public_numbers()
        jwk = {
            "kty": "EC",
            "crv": "P-256",
            "x": base64url_encode(numbers.x.to_bytes(32, "big")),
            "y": base64url_encode(numbers.y.to_bytes(32, "big")),
        }
        jwk["kid"] = device_key_id(jwk)
        device_id = "11111111-1111-1111-1111-111111111111"
        self.service.register_push(
            device_id,
            "valid-token",
            {
                "provider": "fcm",
                "installation_id": "installation-secret",
                "authentik_device_token": "valid-agent-token",
                "mode": "personal",
                "notification_privacy": "standard",
                "app_version": "0.6.0",
                "key_id": jwk["kid"],
                "public_key_jwk": jwk,
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

        timestamp = str(int(time.time()))
        detail_path = f"/v1/notifications/{result['event_id']}"
        detail_nonce = "detail-nonce-0123456789"
        detail_canonical = canonical_device_request(
            "GET", detail_path, device_id, jwk["kid"], timestamp, detail_nonce
        )
        detail_signature = base64url_encode(
            private_key.sign(detail_canonical, ec.ECDSA(hashes.SHA256()))
        )
        detail = self.service.notification_detail(
            result["event_id"],
            device_id,
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

    def test_foreground_login_approval_is_device_signed_and_one_time(self) -> None:
        private_key = ec.generate_private_key(ec.SECP256R1())
        numbers = private_key.public_key().public_numbers()
        jwk = {
            "kty": "EC",
            "crv": "P-256",
            "x": base64url_encode(numbers.x.to_bytes(32, "big")),
            "y": base64url_encode(numbers.y.to_bytes(32, "big")),
        }
        jwk["kid"] = device_key_id(jwk)
        device_id = "11111111-1111-1111-1111-111111111111"
        self.service.register_auth_channel(
            device_id,
            "valid-token",
            {
                "authentik_device_token": "valid-agent-token",
                "mode": "personal",
                "app_version": "0.9.0",
                "key_id": jwk["kid"],
                "public_key_jwk": jwk,
            },
        )

        result: list[bool] = []
        worker = threading.Thread(
            target=lambda: result.append(
                self.service.request_login_approval(
                    subject="authentik-user-1",
                    application="Zimbra",
                    domain="id.example.invalid",
                    display_username="test.user",
                    source_ip="192.0.2.10",
                    timeout_seconds=5,
                )
            )
        )
        worker.start()

        pending = None
        for _ in range(50):
            pending = self.store.pending_auth_request_for_device(device_id)
            if pending is not None:
                break
            time.sleep(0.02)
        self.assertIsNotNone(pending)

        pending_path = "/v1/auth/requests/pending"
        timestamp = str(int(time.time()))
        nonce = "pending-request-nonce-1234"
        canonical = canonical_device_request(
            "GET", pending_path, device_id, jwk["kid"], timestamp, nonce
        )
        signature = base64url_encode(
            private_key.sign(canonical, ec.ECDSA(hashes.SHA256()))
        )
        response = self.service.pending_login_approval(
            device_id=device_id,
            key_id=jwk["kid"],
            timestamp=timestamp,
            nonce=nonce,
            signature=signature,
            path=pending_path,
        )
        request_id = response["request"]["request_id"]
        self.assertEqual("Zimbra", response["request"]["application"])

        decision_path = f"/v1/auth/requests/{request_id}/decision"
        decision_body = b'{"decision":"approve"}'
        decision_timestamp = str(int(time.time()))
        decision_nonce = "decision-request-nonce-123"
        decision_canonical = canonical_device_request(
            "POST",
            decision_path,
            device_id,
            jwk["kid"],
            decision_timestamp,
            decision_nonce,
            decision_body,
        )
        decision_signature = base64url_encode(
            private_key.sign(decision_canonical, ec.ECDSA(hashes.SHA256()))
        )
        self.service.decide_login_approval(
            request_id=request_id,
            approved=True,
            raw_body=decision_body,
            device_id=device_id,
            key_id=jwk["kid"],
            timestamp=decision_timestamp,
            nonce=decision_nonce,
            signature=decision_signature,
            path=decision_path,
        )
        worker.join(timeout=2)
        self.assertEqual([True], result)
        self.assertIsNone(self.store.pending_auth_request_for_device(device_id))

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
