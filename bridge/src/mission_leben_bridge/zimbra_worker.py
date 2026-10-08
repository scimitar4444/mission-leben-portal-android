from __future__ import annotations

import json
import hashlib
import logging
import os
import signal
import threading
import time
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any, Callable

from .source_client import BridgeSourceClient
from .calendar_target import calendar_target
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
        account_map: dict[str, dict[str, Any]],
        timezone_name: str = "Europe/Berlin",
        heartbeat_path: Path | None = None,
        account_map_loader: Callable[[], dict[str, dict[str, Any]]] | None = None,
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
        next_calendar_scan = 0.0
        scanned_map: dict[str, dict[str, Any]] = {}
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
                waitset_id, sequence, active_accounts = self._create_waitset()
                self._touch_heartbeat()
                LOGGER.info(
                    "Zimbra WaitSet created for %d mapped accounts; %d inaccessible accounts isolated",
                    len(active_accounts), len(self.account_map) - len(active_accounts),
                )
                now = time.monotonic()
                if now >= next_calendar_scan:
                    scan_accounts = active_accounts
                    next_calendar_scan = now + 3600
                else:
                    scan_accounts = {account for account in active_accounts if scanned_map.get(account) != self.account_map[account]}
                if scan_accounts:
                    self._initial_calendar_scan(scan_accounts)
                # Rechecking an isolated account must not turn the hourly
                # calendar safety scan into a full scan every five minutes.
                scanned_map = {account: dict(self.account_map[account]) for account in active_accounts}
                retry_isolated_at = time.monotonic() + 300 if len(active_accounts) < len(self.account_map) else None
                backoff = 2
                while not self.stop_event.is_set():
                    sequence, changed_accounts = self.soap.wait(waitset_id, sequence, timeout_seconds=60)
                    self._touch_heartbeat()
                    for account_id in changed_accounts:
                        if account_id in active_accounts:
                            self._scan_account(account_id)
                    if time.monotonic() >= next_calendar_scan:
                        self._initial_calendar_scan(active_accounts)
                        next_calendar_scan = time.monotonic() + 3600
                    if retry_isolated_at is not None and time.monotonic() >= retry_isolated_at:
                        LOGGER.info("Rechecking isolated Zimbra accounts")
                        break
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

    def _create_waitset(self) -> tuple[str, str, set[str]]:
        accounts = list(self.account_map)
        try:
            waitset_id, sequence = self.soap.create_waitset(accounts)
            return waitset_id, sequence, set(accounts)
        except ZimbraSoapError as error:
            if error.code not in {"service.PERM_DENIED", "account.NO_SUCH_ACCOUNT"}:
                raise
        # Only account-specific SOAP faults may trigger isolation. Network,
        # authentication and server failures must retain the normal backoff.
        # Probe halves, not every mailbox, so one denied account among 2,000
        # needs logarithmically many requests. Every successful probe is freed.
        def accessible(batch: list[str]) -> list[str]:
            if not batch or self.stop_event.is_set():
                return []
            try:
                probe_id, _ = self.soap.create_waitset(batch)
            except ZimbraSoapError as error:
                if error.code not in {"service.PERM_DENIED", "account.NO_SUCH_ACCOUNT"}:
                    raise
                if len(batch) == 1:
                    LOGGER.warning("Isolating inaccessible Zimbra account %s (%s)", batch[0], error.code)
                    return []
                middle = len(batch) // 2
                return accessible(batch[:middle]) + accessible(batch[middle:])
            self.soap.destroy_waitset(probe_id)
            return batch

        if len(accounts) == 1:
            allowed: list[str] = []
        else:
            middle = len(accounts) // 2
            allowed = accessible(accounts[:middle]) + accessible(accounts[middle:])
        if not allowed:
            raise ZimbraSoapError("No mapped Zimbra mailbox is accessible", code="service.PERM_DENIED")
        waitset_id, sequence = self.soap.create_waitset(allowed)
        return waitset_id, sequence, set(allowed)

    def _touch_heartbeat(self) -> None:
        if self.heartbeat_path is not None:
            self.heartbeat_path.write_text(str(int(time.time())), encoding="ascii")

    def _initial_calendar_scan(self, account_ids: set[str] | None = None) -> None:
        for account_id in sorted(account_ids if account_ids is not None else self.account_map):
            if self.stop_event.is_set():
                return
            try:
                self._scan_calendar(account_id)
            except Exception:
                LOGGER.exception("initial calendar scan failed for account %s", account_id)

    def _scan_account(self, account_id: str) -> None:
        try:
            cutoff = int((time.time() - 20 * 60) * 1000)
            messages = [message for message in self.soap.recent_messages(account_id) if message.received_millis >= cutoff]
            self._publish_messages(account_id, messages)
            self._scan_calendar(account_id)
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
                    "target_id": message.message_id,
                    "display_at": received.isoformat().replace("+00:00", "Z"),
                    "expires_at": (received + timedelta(days=7)).isoformat().replace("+00:00", "Z"),
                }
            )

    def _scan_calendar(self, account_id: str) -> None:
        appointments = self.soap.upcoming_appointments(account_id)
        self._publish_appointments(account_id, appointments)
        if self.account_map[account_id].get("personal_calendar") is not True:
            return
        # At the SOAP result limit we cannot prove that this is a complete
        # snapshot. Never replace the phone calendar with a truncated list.
        if len(appointments) >= 100:
            LOGGER.warning("Zimbra calendar snapshot reached the result limit for account %s", account_id)
            return
        events = []
        for appointment in appointments:
            key = f"{account_id}:{appointment.appointment_id}:{appointment.start_millis}"
            event_id = hashlib.sha256(key.encode()).hexdigest()[:32]
            events.append({
                "id": event_id,
                "title": appointment.subject or "Termin",
                "location": appointment.location,
                "start_millis": appointment.start_millis,
                "end_millis": appointment.start_millis + max(appointment.duration_millis, 60_000),
                "all_day": appointment.all_day,
            })
        self.bridge.publish_calendar_snapshot({
            "user_subject": self.account_map[account_id]["subject"],
            "events": events,
        })

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
                    "target_id": calendar_target(
                        appointment.invite_id,
                        appointment.recurrence_id,
                        appointment.start_millis,
                        appointment.start_millis + (appointment.duration_millis if appointment.duration_millis > 0 else duration),
                    ),
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


def _account_map(path: Path | None = None) -> dict[str, dict[str, Any]]:
    map_path = path or Path(_required_env("ZIMBRA_ACCOUNT_MAP_FILE"))
    value: Any = json.loads(map_path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise RuntimeError("ZIMBRA_ACCOUNT_MAP_FILE must contain an object")
    result: dict[str, dict[str, Any]] = {}
    for account_id, mapping in value.items():
        if not isinstance(mapping, dict) or not str(mapping.get("subject", "")).strip():
            raise RuntimeError(f"Zimbra account mapping {account_id!r} has no Authentik subject")
        if "personal_calendar" in mapping and type(mapping["personal_calendar"]) is not bool:
            raise RuntimeError(f"Zimbra account mapping {account_id!r} has invalid calendar assignment")
        result[str(account_id)] = {
            "subject": str(mapping["subject"]),
            "email": str(mapping.get("email", "")),
            "personal_calendar": mapping.get("personal_calendar") is True,
        }
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
