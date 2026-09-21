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
from mission_leben_bridge.nextcloud_announcements import AnnouncementFetchError
from mission_leben_bridge.ntfy import NtfyCredentials
from mission_leben_bridge.service import BridgeService
from mission_leben_bridge.store import Store


class FakeAuthentik:
    def user_info(self, access_token: str) -> UserInfo:
        if access_token != "valid-token":
            raise AssertionError("unexpected test token")
        return UserInfo(
            "authentik-user-1",
            "user@example.invalid",
            "Test User",
            frozenset({"open_talk", "device_profile_switch"}),
        )

    def device_id(self, agent_token: str) -> str:
        if agent_token != "valid-agent-token":
            raise AssertionError("unexpected agent token")
        return "11111111-1111-1111-1111-111111111111"


class FakeNtfy:
    configured = True
    public_base_url = "https://push.example.invalid"

    def __init__(self) -> None:
        self.messages: list[tuple[str, str, dict[str, str]]] = []
        self.revoked: list[tuple[str, str]] = []

    def provision(self, device_id: str) -> NtfyCredentials:
        return NtfyCredentials(
            public_base_url=self.public_base_url,
            topic="ml-device-topic-0123456789",
            subscribe_token="tk_" + "s" * 29,
            publish_token="tk_" + "p" * 29,
            reader_username="mlr_device012345678901234567",
            writer_username="mlw_device012345678901234567",
        )

    def send(self, topic: str, publish_token: str, data: dict[str, str]) -> None:
        self.messages.append((topic, publish_token, data))

    def revoke(self, reader_username: str, writer_username: str) -> None:
        self.revoked.append((reader_username, writer_username))


class FakeAnnouncements:
    configured = True

    def __init__(self) -> None:
        self.fail = False
        self.calls = 0

    def fetch(self, *, user_id: str = "", email: str = "") -> list[dict[str, object]]:
        self.calls += 1
        if self.fail:
            raise AnnouncementFetchError("offline")
        return [
            {
                "id": 12,
                "subject": "Maintenance",
                "message": "Sigma is temporarily unavailable.\r\nPlease try again later.\n\nThank you.",
                "author": "IT",
                "time": int(time.time()),
                "delete_time": 0,
            }
        ]


class ServiceTest(unittest.TestCase):
    def setUp(self) -> None:
        self.directory = tempfile.TemporaryDirectory()
        self.store = Store(
            Path(self.directory.name) / "bridge.sqlite3",
            b"h" * 32,
            SecretBox(b"d" * 32),
        )
        self.ntfy = FakeNtfy()
        self.service = BridgeService(self.store, FakeAuthentik(), self.ntfy, ())

    def tearDown(self) -> None:
        self.directory.cleanup()

    def test_authentik_device_registration_and_minimal_ntfy_payload(self) -> None:
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
        subscription = self.service.register_push(
            device_id,
            "valid-token",
            {
                "provider": "ntfy",
                "authentik_device_token": "valid-agent-token",
                "mode": "personal",
                "notification_privacy": "standard",
                "app_version": "0.6.0",
                "key_id": jwk["kid"],
                "public_key_jwk": jwk,
            },
        )
        self.assertEqual("https://push.example.invalid", subscription["base_url"])
        self.assertEqual("ml-device-topic-0123456789", subscription["topic"])
        self.assertNotIn("publish_token", subscription)
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
        self.assertEqual("ml-device-topic-0123456789", self.ntfy.messages[0][0])
        self.assertEqual(
            {"action", "event_id", "event_type", "revision"},
            set(self.ntfy.messages[0][2]),
        )
        self.assertNotIn("Confidential", str(self.ntfy.messages[0][2]))

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

    def test_detailed_privacy_releases_preview_only_after_signed_fetch(self) -> None:
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
                "provider": "ntfy",
                "authentik_device_token": "valid-agent-token",
                "mode": "personal",
                "notification_privacy": "detailed",
                "app_version": "0.11.2",
                "key_id": jwk["kid"],
                "public_key_jwk": jwk,
            },
        )
        result = self.service.ingest_event(
            "zimbra",
            {
                "source_event_id": "mail:account:detailed",
                "user_subject": "authentik-user-1",
                "event_type": "open_mail",
                "title": "Sender Name",
                "summary": "Confidential subject",
                "preview": "Confidential preview",
            },
        )
        self.assertNotIn("Confidential", str(self.ntfy.messages[0][2]))

        timestamp = str(int(time.time()))
        path = f"/v1/notifications/{result['event_id']}"
        nonce = "detailed-nonce-0123456789"
        canonical = canonical_device_request(
            "GET", path, device_id, jwk["kid"], timestamp, nonce
        )
        signature = base64url_encode(
            private_key.sign(canonical, ec.ECDSA(hashes.SHA256()))
        )
        detail = self.service.notification_detail(
            result["event_id"],
            device_id,
            jwk["kid"],
            timestamp,
            nonce,
            signature,
            path,
        )
        self.assertEqual("Confidential preview", detail["preview"])

    def test_communication_off_suppresses_content_but_not_login_approval(self) -> None:
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
                "provider": "ntfy",
                "authentik_device_token": "valid-agent-token",
                "mode": "personal",
                "notification_privacy": "standard",
                "communication_notifications_enabled": False,
                "app_version": "0.11.4",
                "key_id": jwk["kid"],
                "public_key_jwk": jwk,
            },
        )
        event = self.service.ingest_event(
            "zimbra",
            {
                "source_event_id": "mail:account:communication-off",
                "user_subject": "authentik-user-1",
                "event_type": "open_mail",
                "title": "Sender",
                "summary": "Subject",
            },
        )
        self.assertEqual(0, event["dispatched"])
        self.assertEqual([], self.ntfy.messages)

        result: list[bool] = []
        worker = threading.Thread(
            target=lambda: result.append(
                self.service.request_login_approval(
                    subject="authentik-user-1",
                    application="Zimbra",
                    domain="id.example.invalid",
                    display_username="test.user",
                    source_ip="192.0.2.10",
                    timeout_seconds=1,
                )
            )
        )
        worker.start()
        for _ in range(50):
            if self.ntfy.messages:
                break
            time.sleep(0.02)
        self.assertEqual("fetch_login_approval", self.ntfy.messages[0][2]["action"])
        worker.join(timeout=2)
        self.assertEqual([False], result)

    def test_quiet_hours_support_overnight_and_daytime_ranges(self) -> None:
        berlin_noon = int(datetime(2026, 1, 15, 11, 0, tzinfo=timezone.utc).timestamp())
        berlin_late = int(datetime(2026, 1, 15, 22, 0, tzinfo=timezone.utc).timestamp())
        overnight = {
            "communication_enabled": 1,
            "quiet_hours_enabled": 1,
            "quiet_start_minutes": 22 * 60,
            "quiet_end_minutes": 6 * 60,
            "timezone": "Europe/Berlin",
        }
        self.assertTrue(self.service.communication_allowed(overnight, current_epoch=berlin_noon))
        self.assertFalse(self.service.communication_allowed(overnight, current_epoch=berlin_late))
        disabled = {**overnight, "communication_enabled": 0, "quiet_hours_enabled": 0}
        self.assertFalse(self.service.communication_allowed(disabled, current_epoch=berlin_noon))

    def test_shared_device_is_forced_to_minimal_privacy(self) -> None:
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
                "provider": "ntfy",
                "authentik_device_token": "valid-agent-token",
                "mode": "shared",
                "notification_privacy": "detailed",
                "calendar_reminder_minutes": 30,
                "app_version": "0.11.2",
                "key_id": jwk["kid"],
                "public_key_jwk": jwk,
            },
        )
        registration = self.store.get_registration(device_id)
        self.assertIsNotNone(registration)
        self.assertEqual("minimal", registration["privacy"])
        self.assertEqual(15, registration["calendar_reminder_minutes"])

        result = self.service.ingest_event(
            "zimbra",
            {
                "source_event_id": "mail:account:shared",
                "user_subject": "authentik-user-1",
                "event_type": "open_mail",
                "title": "Sender Name",
                "summary": "Confidential subject",
                "preview": "Confidential preview",
            },
        )
        timestamp = str(int(time.time()))
        path = f"/v1/notifications/{result['event_id']}"
        nonce = "shared-nonce-0123456789"
        canonical = canonical_device_request(
            "GET", path, device_id, jwk["kid"], timestamp, nonce
        )
        signature = base64url_encode(
            private_key.sign(canonical, ec.ECDSA(hashes.SHA256()))
        )
        with self.assertRaisesRegex(Exception, "details are disabled"):
            self.service.notification_detail(
                result["event_id"],
                device_id,
                jwk["kid"],
                timestamp,
                nonce,
                signature,
                path,
            )

    def test_capabilities_and_talk_handoff_are_authorized_server_side(self) -> None:
        service = BridgeService(
            self.store,
            FakeAuthentik(),
            self.ntfy,
            ({"id": "room-display", "name": "Raumdisplay", "location": "Zentrale", "online": True},),
        )
        self.assertEqual(
            ["device_profile_switch", "open_talk"],
            service.capabilities("valid-token"),
        )
        self.assertEqual(
            "room-display",
            service.link_targets("valid-token", "open_talk")[0]["id"],
        )
        self.assertEqual(
            "accepted",
            service.create_handoff(
                "valid-token",
                {
                    "action": "open_talk",
                    "target_device_id": "room-display",
                    "room_token": "abcdef123456",
                },
            )["status"],
        )

    def test_talk_handoff_rejects_authenticated_user_without_capability(self) -> None:
        class UnprivilegedAuthentik(FakeAuthentik):
            def user_info(self, access_token: str) -> UserInfo:
                user = super().user_info(access_token)
                return UserInfo(user.subject, user.email, user.display_name)

        service = BridgeService(
            self.store,
            UnprivilegedAuthentik(),
            self.ntfy,
            ({"id": "room-display", "name": "Raumdisplay", "location": "Zentrale", "online": True},),
        )
        self.assertEqual([], service.capabilities("valid-token"))
        with self.assertRaisesRegex(Exception, "required capability"):
            service.link_targets("valid-token", "open_talk")
        with self.assertRaisesRegex(Exception, "required capability"):
            service.create_handoff(
                "valid-token",
                {
                    "action": "open_talk",
                    "target_device_id": "room-display",
                    "room_token": "abcdef123456",
                },
            )

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
        self.assertEqual([], self.ntfy.messages)
        self.assertEqual(0, self.service.dispatch_due_events())

    def test_personal_calendar_reminder_reschedules_pending_event(self) -> None:
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

        def register(reminder_minutes: int) -> None:
            self.service.register_push(
                device_id,
                "valid-token",
                {
                    "provider": "ntfy",
                    "authentik_device_token": "valid-agent-token",
                    "mode": "personal",
                    "notification_privacy": "standard",
                    "calendar_reminder_minutes": reminder_minutes,
                    "app_version": "0.11.2",
                    "key_id": jwk["kid"],
                    "public_key_jwk": jwk,
                },
            )

        register(5)
        starts_at = datetime.now(timezone.utc) + timedelta(minutes=20)
        result = self.service.ingest_event(
            "zimbra",
            {
                "source_event_id": "calendar:account:personal-reminder",
                "user_subject": "authentik-user-1",
                "event_type": "open_calendar",
                "title": "Team meeting",
                "summary": "In twenty minutes",
                "display_at": starts_at.isoformat(),
                "expires_at": (starts_at + timedelta(hours=1)).isoformat(),
            },
        )
        self.assertEqual(0, result["dispatched"])
        self.assertEqual(0, self.service.dispatch_due_events())

        register(30)
        self.assertEqual(1, self.service.dispatch_due_events())
        self.assertEqual(result["event_id"], self.ntfy.messages[0][2]["event_id"])

    def test_announcements_are_cached_per_authenticated_subject(self) -> None:
        client = FakeAnnouncements()
        service = BridgeService(
            self.store,
            FakeAuthentik(),
            self.ntfy,
            (),
            client,  # type: ignore[arg-type]
            announcement_cache_ttl_seconds=-1,
            announcement_stale_ttl_seconds=86_400,
        )
        live = service.announcements("valid-token")
        self.assertFalse(live["stale"])
        self.assertEqual("Maintenance", live["results"][0]["subject"])
        self.assertEqual(
            "Sigma is temporarily unavailable.\nPlease try again later.\n\nThank you.",
            live["results"][0]["message"],
        )
        client.fail = True
        cached = service.announcements("valid-token")
        self.assertTrue(cached["stale"])
        self.assertTrue(cached["cache_hit"])
        self.assertEqual("Maintenance", cached["results"][0]["subject"])

    def test_offboarding_removes_personal_data_and_blocks_new_events(self) -> None:
        subject = "authentik-user-1"
        device_id = "11111111-1111-1111-1111-111111111111"
        self.store.upsert_user(subject, "user@example.invalid", "Test User")
        private_key = ec.generate_private_key(ec.SECP256R1())
        numbers = private_key.public_key().public_numbers()
        jwk = {
            "kty": "EC",
            "crv": "P-256",
            "x": base64url_encode(numbers.x.to_bytes(32, "big")),
            "y": base64url_encode(numbers.y.to_bytes(32, "big")),
        }
        jwk["kid"] = device_key_id(jwk)
        self.store.register_push(
            device_id=device_id,
            subject=subject,
            subscribe_token="tk_" + "s" * 29,
            publish_token="tk_" + "p" * 29,
            topic="ml-offboarding-topic-012345",
            reader_username="mlr_offboardingdevice0000000",
            writer_username="mlw_offboardingdevice0000000",
            agent_token="valid-agent-token",
            key_id=jwk["kid"],
            public_jwk=jwk,
            mode="personal",
            privacy="minimal",
            app_version="0.10.6",
        )
        event, _ = self.store.put_event(
            {
                "source": "zimbra",
                "source_event_id": "offboarding-test",
                "subject": subject,
                "event_type": "open_mail",
                "title": "Personal",
                "summary": "Personal data",
                "preview": "Personal data",
                "display_at": None,
                "expires_at": None,
                "expires_epoch": int(time.time()) + 3600,
                "deliver_epoch": int(time.time()),
            }
        )
        self.store.record_delivery(event["event_id"], device_id)
        self.store.consume_nonce(device_id, "offboarding-nonce-1234", int(time.time()) + 180)
        self.store.create_handoff(subject, device_id, "room-display", "abcdef123456", 30)
        self.store.create_auth_request(
            subject=subject,
            application="Zimbra",
            domain="id.example.invalid",
            display_username="test.user",
            source_ip="192.0.2.10",
            ttl=60,
        )
        self.store.put_announcement_cache(
            subject,
            [{"id": 1, "subject": "Internal", "delete_time": 0}],
            int(time.time()),
        )

        preview = self.service.offboard_subject(subject, dry_run=True)
        self.assertFalse(preview["applied"])
        self.assertEqual(1, preview["registrations"])
        self.assertEqual(1, preview["events"])
        self.assertEqual(1, preview["auth_requests"])
        self.assertEqual(1, preview["announcement_cache"])
        self.assertEqual(1, preview["security_signal_targets"])
        self.assertEqual([], self.ntfy.messages)
        self.assertIsNotNone(self.store.get_registration(device_id))

        result = self.service.offboard_subject(subject)
        self.assertTrue(result["applied"])
        self.assertEqual(1, result["nonces"])
        self.assertEqual(1, result["deliveries"])
        self.assertEqual(1, result["handoffs"])
        self.assertEqual(1, result["security_signals_sent"])
        self.assertEqual(
            (
                "ml-offboarding-topic-012345",
                "tk_" + "p" * 29,
                {"action": "refresh_security_state"},
            ),
            self.ntfy.messages[0],
        )
        self.assertEqual(
            [("mlr_offboardingdevice0000000", "mlw_offboardingdevice0000000")],
            self.ntfy.revoked,
        )
        self.assertIsNone(self.store.get_registration(device_id))
        self.assertFalse(self.store.user_active_state(subject))

        with self.store._connect() as connection:
            user = connection.execute(
                "SELECT email, display_name, active FROM users WHERE subject = ?", (subject,)
            ).fetchone()
            self.assertEqual(("", "", 0), tuple(user))
            for table in (
                "push_registrations",
                "request_nonces",
                "notification_events",
                "event_deliveries",
                "handoffs",
                "auth_requests",
                "announcement_cache",
            ):
                self.assertEqual(0, connection.execute(f"SELECT COUNT(*) FROM {table}").fetchone()[0])

        with self.assertRaisesRegex(Exception, "user is inactive"):
            self.service.ingest_event(
                "zimbra",
                {
                    "source_event_id": "mail-after-offboarding",
                    "user_subject": subject,
                    "event_type": "open_mail",
                },
            )

        repeated = self.service.offboard_subject(subject)
        self.assertTrue(repeated["applied"])
        self.assertEqual(0, repeated["registrations"])
        self.assertEqual(0, repeated["events"])

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
        self.assertEqual("Team IT", adapter._room_label("Team IT"))

    def test_talk_direct_room_ids_are_replaced_with_a_human_label(self) -> None:
        class RecordingService:
            def __init__(self) -> None:
                self.events: list[tuple[str, dict[str, object]]] = []

            def ingest_event(self, source: str, event: dict[str, object]) -> dict[str, object]:
                self.events.append((source, event))
                return {"created": True}

        secret = b"talk-bot-secret"
        recording_service = RecordingService()
        adapter = NextcloudTalkWebhook(
            recording_service,  # type: ignore[arg-type]
            self.store,
            secret,
            "https://cloud.example.invalid",
            {"room1": ("authentik-user-1",)},
            {},
        )
        body = json.dumps(
            {
                "type": "Create",
                "actor": {"type": "Person", "id": "users/alice", "name": "Alice"},
                "object": {"type": "Note", "id": "direct-42", "content": "Hallo"},
                "target": {
                    "type": "Collection",
                    "id": "room1",
                    "name": json.dumps(
                        [
                            "3818C49A-A82B-41BC-886C-9C6D7DEEFE89",
                            "84D54352-FD8B-4F7A-9B74-75FDE2B023C8",
                        ],
                        separators=(",", ":"),
                    ),
                },
            },
            separators=(",", ":"),
        ).encode()
        random_value = "C" * 63 + "/"
        signature = hmac.new(secret, random_value.encode() + body, hashlib.sha256).hexdigest()

        result = adapter.receive(
            body,
            random_value,
            signature,
            "https://cloud.example.invalid",
        )

        self.assertEqual(1, result["events"])
        self.assertEqual("Direktnachricht", recording_service.events[0][1]["summary"])

    def test_talk_call_events_do_not_create_notifications(self) -> None:
        secret = b"talk-bot-secret"
        adapter = NextcloudTalkWebhook(
            self.service,
            self.store,
            secret,
            "https://cloud.example.invalid",
            {"room1": ("authentik-user-1",)},
            {},
        )
        body = json.dumps(
            {
                "type": "Create",
                "actor": {"type": "Person", "id": "users/alice", "name": "Alice"},
                "object": {"type": "Call", "id": "call-42"},
                "target": {"type": "Collection", "id": "room1", "name": "Team IT"},
            },
            separators=(",", ":"),
        ).encode()
        random_value = "B" * 64
        signature = hmac.new(secret, random_value.encode() + body, hashlib.sha256).hexdigest()

        result = adapter.receive(
            body,
            random_value,
            signature,
            "https://cloud.example.invalid",
        )

        self.assertEqual({"accepted": True, "events": 0}, result)
        self.assertEqual([], self.ntfy.messages)

    def test_dynamic_talk_membership_excludes_the_sender(self) -> None:
        class Participants:
            def users(self, room_token: str) -> set[str]:
                self.room_token = room_token
                return {"alice", "bob"}

        class Directory:
            def talk_subjects(self, users: set[str]) -> tuple[str, ...]:
                values = {"alice": "authentik-user-1", "bob": "authentik-user-2"}
                return tuple(sorted(values[user] for user in users if user in values))

        secret = b"talk-bot-secret"
        adapter = NextcloudTalkWebhook(
            self.service,
            self.store,
            secret,
            "https://cloud.example.invalid",
            {},
            {},
            Participants(),  # type: ignore[arg-type]
            Directory(),  # type: ignore[arg-type]
        )
        body = json.dumps(
            {
                "type": "Create",
                "actor": {"type": "Person", "id": "users/alice", "name": "Alice"},
                "object": {"type": "Note", "id": "43", "content": "Hallo"},
                "target": {"type": "Collection", "id": "room1", "name": "Team IT"},
            },
            separators=(",", ":"),
        ).encode()
        random_value = "C" * 64
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
