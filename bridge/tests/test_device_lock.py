from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from mission_leben_bridge.ntfy import NtfyCredentials
from mission_leben_bridge.offboard import lock_device
from mission_leben_bridge.security import SecretBox
from mission_leben_bridge.store import Store


class FakeNtfy:
    configured = True
    public_base_url = "https://push.example.invalid"

    def __init__(self) -> None:
        self.messages: list[tuple[str, str, dict[str, str]]] = []
        self.revoked: list[tuple[str, str]] = []

    def provision(self, device_id: str) -> NtfyCredentials:
        raise AssertionError("provision must not be called while locking")

    def send(self, topic: str, publish_token: str, data: dict[str, str]) -> None:
        self.messages.append((topic, publish_token, data))

    def revoke(self, reader_username: str, writer_username: str) -> None:
        self.revoked.append((reader_username, writer_username))


class DeviceLockTest(unittest.TestCase):
    def setUp(self) -> None:
        self.directory = tempfile.TemporaryDirectory()
        self.store = Store(
            Path(self.directory.name) / "bridge.sqlite3",
            b"h" * 32,
            SecretBox(b"d" * 32),
        )
        self.ntfy = FakeNtfy()
        self.subject = "authentik-user-1"
        self.locked_device = "11111111-1111-1111-1111-111111111111"
        self.active_device = "22222222-2222-2222-2222-222222222222"
        self.store.upsert_user(self.subject, "user@example.invalid", "Test User")
        self._register(
            self.locked_device,
            "a" * 24,
            "ml-lock-topic-0123456789",
            "mlr_lockdevice000000000000000",
            "mlw_lockdevice000000000000000",
        )
        self._register(
            self.active_device,
            "b" * 24,
            "ml-active-topic-01234567",
            "mlr_activedevice000000000000",
            "mlw_activedevice000000000000",
        )

    def tearDown(self) -> None:
        self.directory.cleanup()

    def _register(
        self,
        device_id: str,
        key_id: str,
        topic: str,
        reader: str,
        writer: str,
    ) -> None:
        self.store.register_push(
            device_id=device_id,
            subject=self.subject,
            subscribe_token="tk_" + "s" * 29,
            publish_token="tk_" + "p" * 29,
            topic=topic,
            reader_username=reader,
            writer_username=writer,
            agent_token="valid-agent-token",
            key_id=key_id,
            public_jwk={"kid": key_id},
            mode="personal",
            privacy="minimal",
            app_version="0.11.5",
        )

    def test_dry_run_preserves_the_target(self) -> None:
        result = lock_device(self.store, self.ntfy, self.locked_device, dry_run=True)

        self.assertFalse(result["applied"])
        self.assertEqual(1, result["registrations"])
        self.assertTrue(result["security_signal_target"])
        self.assertEqual([], self.ntfy.messages)
        self.assertIsNotNone(self.store.get_registration(self.locked_device))

    def test_lock_signals_and_removes_only_the_selected_device(self) -> None:
        result = lock_device(self.store, self.ntfy, self.locked_device)

        self.assertTrue(result["applied"])
        self.assertEqual(1, result["security_signal_sent"])
        self.assertEqual(1, result["ntfy_identity_revoked"])
        self.assertEqual(
            [
                (
                    "ml-lock-topic-0123456789",
                    "tk_" + "p" * 29,
                    {"action": "refresh_security_state"},
                )
            ],
            self.ntfy.messages,
        )
        self.assertEqual(
            [("mlr_lockdevice000000000000000", "mlw_lockdevice000000000000000")],
            self.ntfy.revoked,
        )
        self.assertIsNone(self.store.get_registration(self.locked_device))
        self.assertIsNotNone(self.store.get_registration(self.active_device))
        self.assertTrue(self.store.user_active_state(self.subject))


if __name__ == "__main__":
    unittest.main()
