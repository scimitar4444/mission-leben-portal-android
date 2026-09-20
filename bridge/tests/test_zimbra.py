from __future__ import annotations

import time
import unittest
import xml.etree.ElementTree as ET
from datetime import datetime, timezone
from unittest.mock import patch

from mission_leben_bridge.zimbra_waitset import (
    ADMIN,
    MAIL,
    ZimbraAppointment,
    ZimbraMessage,
    ZimbraSoapClient,
    format_appointment_summary,
)
from mission_leben_bridge.zimbra_worker import ZimbraWorker


class RecordingSoapClient(ZimbraSoapClient):
    def __init__(self) -> None:
        super().__init__("https://mail.invalid/admin", "https://mail.invalid/mail", "worker@example.invalid", "secret")
        self.calls: list[tuple[str, ET.Element, bool, str | None, int]] = []
        self.responses: list[ET.Element] = []

    def _post(
        self,
        url: str,
        request_element: ET.Element,
        *,
        include_context: bool = True,
        account_id: str | None = None,
        timeout: int = 30,
    ) -> ET.Element:
        self.calls.append((url, request_element, include_context, account_id, timeout))
        return self.responses.pop(0)


class RecordingBridge:
    def __init__(self) -> None:
        self.events: list[dict[str, object]] = []

    def publish(self, payload: dict[str, object]) -> dict[str, object]:
        self.events.append(payload)
        return {"created": True}


class ZimbraSoapClientTest(unittest.TestCase):
    def setUp(self) -> None:
        self.client = RecordingSoapClient()

    def test_authenticate_and_waitset_lifecycle(self) -> None:
        self.client.responses = [
            ET.fromstring(f'<AuthResponse xmlns="{ADMIN}"><authToken>admin-token</authToken></AuthResponse>'),
            ET.fromstring(f'<AdminCreateWaitSetResponse xmlns="{ADMIN}" waitSet="wait-1" seq="7"/>'),
            ET.fromstring(
                f'<AdminWaitSetResponse xmlns="{ADMIN}" waitSet="wait-1" seq="8">'
                '<n id="account-a"/><n id="account-b"/>'
                '</AdminWaitSetResponse>'
            ),
            ET.fromstring(f'<AdminDestroyWaitSetResponse xmlns="{ADMIN}" waitSet="wait-1"/>'),
        ]

        self.client.authenticate()
        waitset_id, sequence = self.client.create_waitset(["account-a", "account-b"])
        next_sequence, changed = self.client.wait(waitset_id, sequence, timeout_seconds=55)
        self.client.destroy_waitset(waitset_id)

        self.assertEqual("admin-token", self.client.auth_token)
        self.assertEqual(("wait-1", "7"), (waitset_id, sequence))
        self.assertEqual(("8", ["account-a", "account-b"]), (next_sequence, changed))
        self.assertFalse(self.client.calls[0][2])
        self.assertEqual(75, self.client.calls[2][4])
        self.assertEqual(["account-a", "account-b"], [node.attrib["id"] for node in self.client.calls[1][1].iter() if node.tag.endswith("}a")])

    def test_parses_message_and_calendar_search_results(self) -> None:
        self.client.auth_token = "admin-token"
        self.client.responses = [
            ET.fromstring(
                f'<SearchResponse xmlns="{MAIL}"><m id="41" d="1700000000000">'
                '<e t="f" a="sender@example.invalid" p="Absender"/><su>Betreff</su><fr>Vorschau</fr>'
                '</m></SearchResponse>'
            ),
            ET.fromstring(
                f'<SearchResponse xmlns="{MAIL}"><appt id="73"><su>Termin</su><loc>Raum 1</loc>'
                '<inst s="1800000000000" dur="3600000"/></appt></SearchResponse>'
            ),
        ]

        messages = self.client.recent_messages("account-a")
        appointments = self.client.upcoming_appointments("account-a")

        self.assertEqual(
            [ZimbraMessage("41", 1700000000000, "Absender", "Betreff", "Vorschau")],
            messages,
        )
        self.assertEqual(
            [ZimbraAppointment("73", 1800000000000, 3600000, "Termin", "Raum 1")],
            appointments,
        )
        self.assertEqual("account-a", self.client.calls[0][3])
        self.assertEqual("account-a", self.client.calls[1][3])


class ZimbraWorkerTest(unittest.TestCase):
    def setUp(self) -> None:
        self.bridge = RecordingBridge()
        self.worker = ZimbraWorker(
            soap=object(),  # type: ignore[arg-type]
            bridge=self.bridge,  # type: ignore[arg-type]
            account_map={"account-a": {"subject": "authentik-subject", "email": "user@example.invalid"}},
            timezone_name="Europe/Berlin",
            reminder_minutes=15,
        )

    def test_mail_event_contains_only_notification_fields(self) -> None:
        received_millis = 1_800_000_000_000

        self.worker._publish_messages(
            "account-a",
            [ZimbraMessage("41", received_millis, "Absender", "Betreff", "Vorschau")],
        )

        event = self.bridge.events[0]
        self.assertEqual("mail:account-a:41", event["source_event_id"])
        self.assertEqual("authentik-subject", event["user_subject"])
        self.assertEqual("open_mail", event["event_type"])
        self.assertEqual("Absender", event["title"])
        self.assertEqual("Betreff", event["summary"])
        self.assertEqual("Vorschau", event["preview"])
        self.assertNotIn("email", event)

    def test_calendar_event_is_scheduled_before_start_and_skips_old_instances(self) -> None:
        start_millis = 1_800_000_000_000
        now = start_millis / 1000 - 3600
        appointments = [
            ZimbraAppointment("future", start_millis, 1_800_000, "Besprechung", "Raum 1"),
            ZimbraAppointment("old", start_millis - 7_200_000, 3_600_000, "Alt", ""),
        ]

        with patch("mission_leben_bridge.zimbra_worker.time.time", return_value=now):
            self.worker._publish_appointments("account-a", appointments)

        self.assertEqual(1, len(self.bridge.events))
        event = self.bridge.events[0]
        self.assertEqual("calendar:account-a:future:1800000000000", event["source_event_id"])
        self.assertEqual("open_calendar", event["event_type"])
        self.assertEqual("Besprechung", event["title"])
        self.assertIn("Raum 1", str(event["summary"]))
        self.assertEqual(
            datetime.fromtimestamp(start_millis / 1000 - 15 * 60, timezone.utc).isoformat().replace("+00:00", "Z"),
            event["deliver_at"],
        )
        self.assertEqual(
            datetime.fromtimestamp(start_millis / 1000 + 3600, timezone.utc).isoformat().replace("+00:00", "Z"),
            event["expires_at"],
        )

    def test_appointment_summary_uses_configured_timezone(self) -> None:
        winter = int(datetime(2026, 1, 15, 10, 0, tzinfo=timezone.utc).timestamp() * 1000)
        self.assertEqual("15.01.2026, 11:00 Uhr · Darmstadt", format_appointment_summary(winter, "Darmstadt", "Europe/Berlin"))


if __name__ == "__main__":
    unittest.main()
