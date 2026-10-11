from __future__ import annotations

import tempfile
import threading
import time
import unittest
from pathlib import Path

from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric import ec

from mission_leben_bridge.offboard import lock_device, offboard_subject
from mission_leben_bridge.security import SecretBox, base64url_encode, canonical_device_request, device_key_id
from mission_leben_bridge.ntfy import NtfyCredentials
from mission_leben_bridge.service import ApiError, BridgeService
from mission_leben_bridge.store import Store
from test_service import FakeAuthentik, FakeNtfy


IDS = ("11111111-1111-1111-1111-111111111111", "22222222-2222-2222-2222-222222222222")
SUBJECT = "authentik-user-1"


class TwoDeviceAuthentik(FakeAuthentik):
    def device_id(self, agent_token):
        return {"agent-first": IDS[0], "agent-second": IDS[1]}[agent_token]


class TwoDeviceNtfy(FakeNtfy):
    def provision(self, device_id):
        suffix = device_id.replace("-", "")
        return NtfyCredentials(public_base_url=self.public_base_url, topic="ml-device-" + suffix,
            subscribe_token="tk_" + "s" * 29, publish_token="tk_" + "p" * 29,
            reader_username="mlr_" + suffix, writer_username="mlw_" + suffix)


class TwoPersonalDevicesTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.store = Store(Path(self.directory.name) / "bridge.sqlite3", b"h" * 32, SecretBox(b"d" * 32))
        self.ntfy = TwoDeviceNtfy()
        self.service = BridgeService(self.store, TwoDeviceAuthentik(), self.ntfy, ())
        self.keys = {}
        for device_id, agent in zip(IDS, ("agent-first", "agent-second")):
            key = ec.generate_private_key(ec.SECP256R1())
            numbers = key.public_key().public_numbers()
            jwk = {"kty": "EC", "crv": "P-256", "x": base64url_encode(numbers.x.to_bytes(32, "big")),
                   "y": base64url_encode(numbers.y.to_bytes(32, "big"))}
            jwk["kid"] = device_key_id(jwk)
            self.keys[device_id] = (key, jwk)
            self.service.register_push(device_id, "valid-token", {"provider": "ntfy", "mode": "personal",
                "authentik_device_token": agent, "key_id": jwk["kid"], "public_key_jwk": jwk,
                "app_version": "0.21.6", "notification_privacy": "standard"})

    def tearDown(self):
        self.directory.cleanup()

    def test_messages_deliver_once_to_each_distinct_device_channel(self):
        for source, event_type in (("zimbra", "open_mail"), ("nextcloud", "open_talk")):
            self.ntfy.messages.clear()
            result = self.service.ingest_event(source, {"source_event_id": source + ":test:two",
                "user_subject": SUBJECT, "event_type": event_type, "title": "Sender", "summary": "Subject"})
            self.assertEqual(2, result["dispatched"])
            self.assertEqual(2, len(self.ntfy.messages))
            self.assertEqual(2, len({message[0] for message in self.ntfy.messages}))
            duplicate = self.service.ingest_event(source, {"source_event_id": source + ":test:two",
                "user_subject": SUBJECT, "event_type": event_type, "title": "Sender", "summary": "Subject"})
            self.assertEqual(0, duplicate["dispatched"])
            self.assertEqual(2, len(self.ntfy.messages))

    def decide(self, device_id, request_id, approved):
        key, jwk = self.keys[device_id]
        path = f"/v1/auth/requests/{request_id}/decision"
        body = b'{"decision":"approve"}' if approved else b'{"decision":"deny"}'
        timestamp = str(int(time.time()))
        nonce = "two-device-decision-" + device_id
        canonical = canonical_device_request("POST", path, device_id, jwk["kid"], timestamp, nonce, body)
        self.service.decide_login_approval(request_id=request_id, approved=approved, raw_body=body,
            device_id=device_id, key_id=jwk["kid"], timestamp=timestamp, nonce=nonce,
            signature=base64url_encode(key.sign(canonical, ec.ECDSA(hashes.SHA256()))), path=path)

    def test_duo_notifies_both_but_only_one_signed_decision_counts(self):
        result = []
        worker = threading.Thread(target=lambda: result.append(self.service.request_login_approval(
            subject=SUBJECT, application="Zimbra", domain="id.example.invalid", display_username="test.user",
            source_ip="192.0.2.1", timeout_seconds=3)))
        worker.start()
        try:
            deadline = time.monotonic() + 2
            while time.monotonic() < deadline and len(self.ntfy.messages) < 2:
                time.sleep(0.01)
            self.assertEqual(2, len(self.ntfy.messages))
            first = self.store.pending_auth_request_for_device(IDS[0])
            second = self.store.pending_auth_request_for_device(IDS[1])
            self.assertIsNotNone(first)
            self.assertEqual(first["request_id"], second["request_id"])
            self.decide(IDS[1], first["request_id"], True)
            with self.assertRaises(ApiError) as error:
                self.decide(IDS[0], first["request_id"], True)
            self.assertEqual(409, error.exception.status)
        finally:
            worker.join(timeout=4)
        self.assertEqual([True], result)
        self.assertIsNone(self.store.pending_auth_request_for_device(IDS[0]))
        self.assertIsNone(self.store.pending_auth_request_for_device(IDS[1]))

    def test_locking_one_phone_does_not_interrupt_peer_push(self):
        lock_device(self.store, self.ntfy, IDS[0])
        self.assertIsNone(self.store.get_registration(IDS[0]))
        self.assertIsNotNone(self.store.get_registration(IDS[1]))
        self.ntfy.messages.clear()
        result = self.service.ingest_event("zimbra", {"source_event_id": "mail:peer:test", "user_subject": SUBJECT,
            "event_type": "open_mail", "title": "Sender", "summary": "Subject"})
        self.assertEqual(1, result["dispatched"])
        self.assertEqual(self.store.get_registration(IDS[1])["ntfy_topic"], self.ntfy.messages[0][0])

    def test_offboarding_signals_and_removes_both_phones(self):
        result = offboard_subject(self.store, self.ntfy, SUBJECT)
        self.assertEqual(2, result["security_signals_sent"])
        self.assertEqual(2, len(self.ntfy.revoked))
        self.assertFalse(self.store.user_active_state(SUBJECT))
        for device_id in IDS:
            self.assertIsNone(self.store.get_registration(device_id))
