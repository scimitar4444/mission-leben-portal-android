from __future__ import annotations

import json
import logging
import os
import signal
import threading
import time
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any, Callable

from .source_client import BridgeSourceClient
from .zimbra_waitset import (
    ZimbraAppointment,
    ZimbraMessage,
    ZimbraSoapClient,
    ZimbraSoapError,
    format_appointment_summary,
    iso_from_millis,
)


LOGGER = logging.getLogger("mission_leben_bridge.zimbra")


class ZimbraWorker:
    def __init__(
        self,
        soap: ZimbraSoapClient,
        bridge: BridgeSourceClient,
        account_map: dict[str, dict[str, str]],
        timezone_name: str = "Europe/Berlin",
        heartbeat_path: Path | None = None,
        account_map_loader: Callable[[], dict[str, dict[str, str]]] | None = None,
    ):
        self.soap = soap
        self.bridge = bridge
        self.account_map = account_map
        self.timezone_name = timezone_name
        self.heartbeat_path = heartbeat_path
        self.account_map_loader = account_map_loader
        self.stop_event = threading.Event()

    def run(self) -> None:
        backoff = 2
        while not self.stop_event.is_set():
            waitset_id = ""
            try:
                if self.account_map_loader is not None:
                    updated_map = self.account_map_loader()
                    if updated_map != self.account_map:
                        LOGGER.info(
                            "Zimbra account mapping changed from %d to %d accounts",
                            len(self.account_map),
                            len(updated_map),
                        )
                        self.account_map = updated_map
                if not self.account_map:
                    self._touch_heartbeat()
                    self.stop_event.wait(30)
                    continue
                self.soap.authenticate()
                waitset_id, sequence = self.soap.create_waitset(list(self.account_map))
                self._touch_heartbeat()
                LOGGER.info("Zimbra WaitSet created for %d mapped accounts", len(self.account_map))
                self._initial_calendar_scan()
                backoff = 2
                while not self.stop_event.is_set():
                    sequence, changed_accounts = self.soap.wait(waitset_id, sequence, timeout_seconds=60)
                    self._touch_heartbeat()
                    for account_id in changed_accounts:
                        if account_id in self.account_map:
                            self._scan_account(account_id)
                    if (
                        self.account_map_loader is not None
                        and self.account_map_loader() != self.account_map
                    ):
                        LOGGER.info("Zimbra account mapping update detected; recreating WaitSet")
                        break
            except (ZimbraSoapError, RuntimeError, OSError, ValueError):
                LOGGER.exception("Zimbra WaitSet cycle failed; reconnecting")
                self.stop_event.wait(backoff)
                backoff = min(backoff * 2, 60)
            finally:
                if waitset_id:
                    try:
                        self.soap.destroy_waitset(waitset_id)
                    except ZimbraSoapError:
                        pass

    def stop(self) -> None:
        self.stop_event.set()

    def _touch_heartbeat(self) -> None:
        if self.heartbeat_path is not None:
            self.heartbeat_path.write_text(str(int(time.time())), encoding="ascii")

    def _initial_calendar_scan(self) -> None:
        for account_id in self.account_map:
            if self.stop_event.is_set():
                return
            try:
                self._publish_appointments(account_id, self.soap.upcoming_appointments(account_id))
            except Exception:
                LOGGER.exception("initial calendar scan failed for account %s", account_id)

    def _scan_account(self, account_id: str) -> None:
        try:
            cutoff = int((time.time() - 20 * 60) * 1000)
            messages = [message for message in self.soap.recent_messages(account_id) if message.received_millis >= cutoff]
            self._publish_messages(account_id, messages)
            self._publish_appointments(account_id, self.soap.upcoming_appointments(account_id))
        except Exception:
            LOGGER.exception("Zimbra change scan failed for account %s", account_id)

    def _publish_messages(self, account_id: str, messages: list[ZimbraMessage]) -> None:
        subject = self.account_map[account_id]["subject"]
        for message in messages:
            received = datetime.fromtimestamp(message.received_millis / 1000, timezone.utc)
            self.bridge.publish(
                {
                    "source_event_id": f"mail:{account_id}:{message.message_id}",
                    "user_subject": subject,
                    "event_type": "open_mail",
                    "title": message.sender or "Neue Mail",
                    "summary": message.subject or "Ohne Betreff",
                    "preview": message.fragment,
                    "display_at": received.isoformat().replace("+00:00", "Z"),
                    "expires_at": (received + timedelta(days=7)).isoformat().replace("+00:00", "Z"),
                }
            )

    def _publish_appointments(self, account_id: str, appointments: list[ZimbraAppointment]) -> None:
        subject = self.account_map[account_id]["subject"]
        now_millis = int(time.time() * 1000)
        for appointment in appointments:
            if appointment.start_millis < now_millis - 5 * 60 * 1000:
                continue
            duration = max(appointment.duration_millis, 60 * 60 * 1000)
            self.bridge.publish(
                {
                    "source_event_id": f"calendar:{account_id}:{appointment.appointment_id}:{appointment.start_millis}",
                    "user_subject": subject,
                    "event_type": "open_calendar",
                    "title": appointment.subject or "Termin",
                    "summary": format_appointment_summary(
                        appointment.start_millis,
                        appointment.location,
                        self.timezone_name,
                    ),
                    "preview": "",
                    "display_at": iso_from_millis(appointment.start_millis),
                    "expires_at": iso_from_millis(appointment.start_millis + duration),
                }
            )


def _required_env(name: str) -> str:
    value = os.getenv(name, "").strip()
    if not value:
        raise RuntimeError(f"{name} must be configured")
    return value


def _password() -> str:
    path = os.getenv("ZIMBRA_ADMIN_PASSWORD_FILE", "").strip()
    if path:
        return Path(path).read_text(encoding="utf-8").strip()
    return _required_env("ZIMBRA_ADMIN_PASSWORD")


def _account_map(path: Path | None = None) -> dict[str, dict[str, str]]:
    map_path = path or Path(_required_env("ZIMBRA_ACCOUNT_MAP_FILE"))
    value: Any = json.loads(map_path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise RuntimeError("ZIMBRA_ACCOUNT_MAP_FILE must contain an object")
    result: dict[str, dict[str, str]] = {}
    for account_id, mapping in value.items():
        if not isinstance(mapping, dict) or not str(mapping.get("subject", "")).strip():
            raise RuntimeError(f"Zimbra account mapping {account_id!r} has no Authentik subject")
        result[str(account_id)] = {"subject": str(mapping["subject"]), "email": str(mapping.get("email", ""))}
    return result


def main() -> None:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")
    admin_url = _required_env("ZIMBRA_ADMIN_SOAP_URL")
    account_map_path = Path(_required_env("ZIMBRA_ACCOUNT_MAP_FILE"))
    dynamic_mapping = os.getenv("ZIMBRA_DYNAMIC_ACCOUNT_MAP", "false").lower() in {
        "1",
        "true",
        "yes",
    }
    initial_map = _account_map(account_map_path)
    if not dynamic_mapping and not initial_map:
        raise RuntimeError("ZIMBRA_ACCOUNT_MAP_FILE must contain a non-empty object")
    worker = ZimbraWorker(
        soap=ZimbraSoapClient(
            admin_soap_url=admin_url,
            mail_soap_url=os.getenv("ZIMBRA_MAIL_SOAP_URL", admin_url.replace("/service/admin/soap", "/service/soap")),
            admin_user=_required_env("ZIMBRA_ADMIN_USER"),
            admin_password=_password(),
        ),
        bridge=BridgeSourceClient(
            _required_env("BRIDGE_BASE_URL"),
            "zimbra",
            _required_env("BRIDGE_INTERNAL_HMAC_SECRET").encode(),
        ),
        account_map=initial_map,
        timezone_name=os.getenv("ZIMBRA_TIMEZONE", "Europe/Berlin"),
        heartbeat_path=Path(os.getenv("ZIMBRA_HEARTBEAT_FILE", "/tmp/zimbra-worker-heartbeat")),
        account_map_loader=(lambda: _account_map(account_map_path)) if dynamic_mapping else None,
    )
    signal.signal(signal.SIGTERM, lambda *_: worker.stop())
    signal.signal(signal.SIGINT, lambda *_: worker.stop())
    worker.run()


if __name__ == "__main__":
    main()
