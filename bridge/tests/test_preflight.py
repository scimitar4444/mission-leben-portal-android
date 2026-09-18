from __future__ import annotations

import base64
import json
import tempfile
import unittest
from pathlib import Path

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import rsa

from mission_leben_bridge.preflight import evaluate


class PreflightTest(unittest.TestCase):
    def setUp(self) -> None:
        self.directory = tempfile.TemporaryDirectory()
        self.root = Path(self.directory.name)
        self.environment = {
            "BRIDGE_INTERNAL_HMAC_SECRET": "h" * 48,
            "BRIDGE_DATA_KEY": base64.urlsafe_b64encode(b"d" * 32).rstrip(b"=").decode(),
            "BRIDGE_DATABASE_PATH": "/data/bridge.sqlite3",
            "BRIDGE_AUTHENTIK_USERINFO_URL": "https://id.example.invalid/application/o/userinfo/",
            "BRIDGE_AUTHENTIK_AGENT_CONFIG_URL": "https://id.example.invalid/api/v3/endpoints/agents/connectors/agent_config/",
        }

    def tearDown(self) -> None:
        self.directory.cleanup()

    def test_optional_components_are_disabled_without_configuration(self) -> None:
        result = evaluate(self.environment)

        self.assertEqual("ready", result["status"])
        self.assertEqual("ready", result["components"]["bridge"]["status"])
        self.assertEqual("disabled", result["components"]["fcm"]["status"])
        self.assertEqual("disabled", result["components"]["zimbra"]["status"])
        self.assertEqual("disabled", result["components"]["talk"]["status"])

    def test_required_disabled_component_is_not_ready(self) -> None:
        result = evaluate(self.environment, {"fcm"})

        self.assertEqual("not_ready", result["status"])
        self.assertEqual("disabled", result["components"]["fcm"]["status"])

    def test_complete_notification_configuration_is_ready_and_does_not_leak_secrets(self) -> None:
        private_key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
        private_pem = private_key.private_bytes(
            serialization.Encoding.PEM,
            serialization.PrivateFormat.PKCS8,
            serialization.NoEncryption(),
        ).decode()
        firebase_path = self.root / "firebase.json"
        firebase_path.write_text(
            json.dumps(
                {
                    "type": "service_account",
                    "project_id": "portal-project",
                    "client_email": "bridge@portal-project.iam.gserviceaccount.com",
                    "private_key": private_pem,
                }
            ),
            encoding="utf-8",
        )
        talk_secret = "talk-secret-do-not-print-0123456789"
        talk_secret_path = self.root / "talk.secret"
        talk_secret_path.write_text(talk_secret, encoding="utf-8")
        zimbra_password = "zimbra-password-do-not-print"
        zimbra_password_path = self.root / "zimbra.password"
        zimbra_password_path.write_text(zimbra_password, encoding="utf-8")
        account_map_path = self.root / "accounts.json"
        account_map_path.write_text(
            json.dumps({"account-id": {"subject": "authentik-subject", "email": "user@example.invalid"}}),
            encoding="utf-8",
        )
        environment = {
            **self.environment,
            "BRIDGE_FIREBASE_PROJECT_ID": "portal-project",
            "GOOGLE_APPLICATION_CREDENTIALS": str(firebase_path),
            "BRIDGE_NEXTCLOUD_BACKEND_URL": "https://cloud.example.invalid",
            "BRIDGE_NEXTCLOUD_TALK_SECRET_FILE": str(talk_secret_path),
            "BRIDGE_TALK_RECIPIENTS_JSON": json.dumps({"room_123": ["authentik-subject"]}),
            "BRIDGE_NEXTCLOUD_USER_SUBJECTS_JSON": json.dumps({"nextcloud-user": "authentik-subject"}),
            "BRIDGE_BASE_URL": "http://bridge:8080",
            "ZIMBRA_ADMIN_SOAP_URL": "https://mailbox.example.invalid:7071/service/admin/soap",
            "ZIMBRA_MAIL_SOAP_URL": "https://mailbox.example.invalid/service/soap",
            "ZIMBRA_ADMIN_USER": "notification-worker@example.invalid",
            "ZIMBRA_ADMIN_PASSWORD_FILE": str(zimbra_password_path),
            "ZIMBRA_ACCOUNT_MAP_FILE": str(account_map_path),
            "ZIMBRA_TIMEZONE": "Europe/Berlin",
            "ZIMBRA_REMINDER_MINUTES": "15",
        }

        result = evaluate(environment, {"fcm", "zimbra", "talk"})
        serialized = json.dumps(result)

        self.assertEqual("ready", result["status"])
        self.assertNotIn(talk_secret, serialized)
        self.assertNotIn(zimbra_password, serialized)
        self.assertNotIn(private_pem, serialized)

    def test_partial_configuration_is_reported_as_invalid(self) -> None:
        environment = {
            **self.environment,
            "BRIDGE_FIREBASE_PROJECT_ID": "portal-project",
            "BRIDGE_NEXTCLOUD_BACKEND_URL": "http://cloud.example.invalid",
            "ZIMBRA_ADMIN_USER": "worker@example.invalid",
        }

        result = evaluate(environment)

        self.assertEqual("not_ready", result["status"])
        self.assertEqual("invalid", result["components"]["fcm"]["status"])
        self.assertEqual("invalid", result["components"]["talk"]["status"])
        self.assertEqual("invalid", result["components"]["zimbra"]["status"])

    def test_invalid_talk_mapping_alone_is_not_treated_as_disabled(self) -> None:
        environment = {**self.environment, "BRIDGE_TALK_RECIPIENTS_JSON": "not-json"}

        result = evaluate(environment)

        self.assertEqual("not_ready", result["status"])
        self.assertEqual("invalid", result["components"]["talk"]["status"])


if __name__ == "__main__":
    unittest.main()
