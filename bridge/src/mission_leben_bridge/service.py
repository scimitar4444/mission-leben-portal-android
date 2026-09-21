from __future__ import annotations

import re
import threading
import time
from datetime import datetime, timezone
from typing import Any
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

from .authentik import AuthenticationError, AuthentikClient, UserInfo
from .nextcloud_announcements import AnnouncementFetchError, NextcloudAnnouncementClient
from .ntfy import NtfyCredentials, NtfyError, NtfyManager, NullNtfyManager
from .offboard import offboard_subject
from .security import canonical_device_request, device_key_id, verify_device_signature
from .store import Store


EVENT_ID = re.compile(r"^[A-Za-z0-9_-]{16,128}$")
AUTH_REQUEST_ID = re.compile(r"^[A-Za-z0-9_-]{24,128}$")
KEY_ID = re.compile(r"^[a-f0-9]{24}$")
ROOM_TOKEN = re.compile(r"^[A-Za-z0-9_-]{6,128}$")
EVENT_TYPES = {"open_mail", "open_calendar", "open_talk"}
PRIVACY_LEVELS = {"minimal", "standard", "detailed"}
CALENDAR_REMINDER_MINUTES = {5, 10, 15, 30}
DEFAULT_CALENDAR_REMINDER_MINUTES = 15
KNOWN_CAPABILITIES = {"open_talk", "device_profile_switch"}


class ApiError(Exception):
    def __init__(self, status: int, message: str):
        super().__init__(message)
        self.status = status
        self.message = message


def _text(value: Any, maximum: int, *, required: bool = False) -> str:
    result = re.sub(r"[\x00-\x1f\x7f]+", " ", str(value or ""))
    result = re.sub(r"\s+", " ", result).strip()[:maximum]
    if required and not result:
        raise ApiError(400, "required text value is missing")
    return result


def _multiline_text(value: Any, maximum: int) -> str:
    result = str(value or "").replace("\r\n", "\n").replace("\r", "\n")
    result = re.sub(r"[\x00-\x09\x0b-\x1f\x7f]", " ", result)
    result = "\n".join(
        re.sub(r"[^\S\n]+", " ", line).strip() for line in result.split("\n")
    ).strip()
    return result[:maximum].rstrip()


def _iso_epoch(value: Any) -> tuple[str | None, int | None]:
    if not value:
        return None, None
    text = str(value).strip()
    try:
        parsed = datetime.fromisoformat(text.replace("Z", "+00:00"))
    except ValueError as error:
        raise ApiError(400, "time values must use ISO 8601") from error
    if parsed.tzinfo is None:
        raise ApiError(400, "time values must contain a timezone")
    return parsed.astimezone(timezone.utc).isoformat().replace("+00:00", "Z"), int(parsed.timestamp())


def _boolean(value: Any, *, name: str) -> bool:
    if isinstance(value, bool):
        return value
    raise ApiError(400, f"{name} must be a boolean")


class BridgeService:
    def __init__(
        self,
        store: Store,
        authentik: AuthentikClient,
        ntfy: NtfyManager | NullNtfyManager,
        talk_targets: tuple[dict[str, Any], ...],
        announcement_client: NextcloudAnnouncementClient | None = None,
        announcement_cache_ttl_seconds: int = 300,
        announcement_stale_ttl_seconds: int = 86_400,
    ):
        self.store = store
        self.authentik = authentik
        self.ntfy = ntfy
        self.talk_targets = tuple(self._normalize_target(target) for target in talk_targets)
        self.announcement_client = announcement_client
        self.announcement_cache_ttl_seconds = announcement_cache_ttl_seconds
        self.announcement_stale_ttl_seconds = announcement_stale_ttl_seconds
        self._dispatch_lock = threading.Lock()

    def authenticate(self, bearer: str) -> UserInfo:
        user = self.authentik.user_info(bearer)
        self.store.upsert_user(user.subject, user.email, user.display_name, active=True)
        return user

    def set_user_active(self, subject: str, active: bool) -> bool:
        if active:
            return self.store.set_user_active(subject, True)
        offboard_subject(self.store, self.ntfy, subject)
        return True

    def offboard_subject(self, subject: str, *, dry_run: bool = False) -> dict[str, object]:
        return offboard_subject(self.store, self.ntfy, subject, dry_run=dry_run)

    def capabilities(self, bearer: str) -> list[str]:
        user = self.authenticate(bearer)
        return sorted(user.capabilities & KNOWN_CAPABILITIES)

    def announcements(self, bearer: str) -> dict[str, Any]:
        user = self.authenticate(bearer)
        now = int(time.time())
        cached = self.store.get_announcement_cache(user.subject)
        if cached is not None:
            values, fetched_at = cached
            if now - fetched_at <= self.announcement_cache_ttl_seconds:
                return self._announcement_response(values, fetched_at, stale=False, cache_hit=True)
        client = self.announcement_client
        if client is None or not client.configured:
            return self._announcement_response([], now, stale=False, cache_hit=False)
        try:
            values = client.fetch(user_id=user.nextcloud_user_id, email=user.email)
        except AnnouncementFetchError as error:
            if cached is not None:
                values, fetched_at = cached
                if now - fetched_at <= self.announcement_stale_ttl_seconds:
                    return self._announcement_response(
                        values, fetched_at, stale=True, cache_hit=True
                    )
            raise ApiError(503, "announcements are temporarily unavailable") from error
        normalized = [self._normalize_announcement(value) for value in values]
        normalized = [value for value in normalized if value is not None]
        self.store.put_announcement_cache(user.subject, normalized, now)
        return self._announcement_response(normalized, now, stale=False, cache_hit=False)

    @staticmethod
    def _normalize_announcement(value: dict[str, Any]) -> dict[str, Any] | None:
        try:
            announcement_id = int(value.get("id", 0))
            created_at = int(value.get("time", 0))
            delete_time = int(value.get("delete_time") or 0)
        except (TypeError, ValueError):
            return None
        subject = _text(value.get("subject"), 200, required=True)
        return {
            "id": announcement_id,
            "subject": subject,
            "message": _multiline_text(value.get("message"), 500),
            "author": _text(value.get("author"), 120),
            "time": max(created_at, 0),
            "delete_time": max(delete_time, 0),
        }

    @staticmethod
    def _announcement_response(
        values: list[dict[str, Any]], fetched_at: int, *, stale: bool, cache_hit: bool
    ) -> dict[str, Any]:
        now = int(time.time())
        active = [
            value
            for value in values
            if int(value.get("delete_time") or 0) == 0
            or int(value.get("delete_time") or 0) > now
        ]
        return {
            "results": active[:7],
            "fetched_at": fetched_at,
            "stale": stale,
            "cache_hit": cache_hit,
        }

    @staticmethod
    def _require_capability(user: UserInfo, capability: str) -> None:
        if capability not in user.capabilities:
            raise ApiError(403, "required capability is missing")

    def register_push(self, device_id: str, bearer: str, payload: dict[str, Any]) -> dict[str, str]:
        user = self.authenticate(bearer)
        privacy = str(payload.get("notification_privacy", "standard"))
        if privacy not in PRIVACY_LEVELS:
            raise ApiError(400, "invalid notification privacy level")
        try:
            calendar_reminder_minutes = int(
                payload.get("calendar_reminder_minutes", DEFAULT_CALENDAR_REMINDER_MINUTES)
            )
        except (TypeError, ValueError) as error:
            raise ApiError(400, "invalid calendar reminder") from error
        if calendar_reminder_minutes not in CALENDAR_REMINDER_MINUTES:
            raise ApiError(400, "calendar reminder must be 5, 10, 15 or 30 minutes")
        communication_enabled = _boolean(
            payload.get("communication_notifications_enabled", True),
            name="communication_notifications_enabled",
        )
        quiet_hours_enabled = _boolean(
            payload.get("quiet_hours_enabled", False),
            name="quiet_hours_enabled",
        )
        try:
            quiet_start_minutes = int(payload.get("quiet_start_minutes", 22 * 60))
            quiet_end_minutes = int(payload.get("quiet_end_minutes", 6 * 60))
        except (TypeError, ValueError) as error:
            raise ApiError(400, "quiet hour values must be minutes since midnight") from error
        if quiet_start_minutes not in range(1440) or quiet_end_minutes not in range(1440):
            raise ApiError(400, "quiet hour values must be between 0 and 1439")
        timezone_name = _text(payload.get("timezone") or "Europe/Berlin", 64, required=True)
        try:
            ZoneInfo(timezone_name)
        except (ValueError, ZoneInfoNotFoundError) as error:
            raise ApiError(400, "unknown timezone") from error
        if payload.get("provider") != "ntfy":
            raise ApiError(400, "only the ntfy provider is supported")
        if not self.ntfy.configured:
            raise ApiError(503, "ntfy is not configured")
        mode = str(payload.get("mode", ""))
        key_id = str(payload.get("key_id", ""))
        jwk = payload.get("public_key_jwk")
        agent_token = _text(payload.get("authentik_device_token"), 4096, required=True)
        if mode not in {"personal", "shared"}:
            raise ApiError(400, "mode must be personal or shared")
        if not KEY_ID.fullmatch(key_id) or not isinstance(jwk, dict):
            raise ApiError(400, "a valid communication key is required")
        try:
            derived_key_id = device_key_id(jwk)
        except (ValueError, KeyError) as error:
            raise ApiError(400, "the communication public key is invalid") from error
        if derived_key_id != key_id or jwk.get("kid") != key_id:
            raise ApiError(400, "key_id does not match the communication public key")
        try:
            verified_device_id = self.authentik.device_id(agent_token)
        except AuthenticationError as error:
            raise ApiError(403 if error.permanent else 503, str(error)) from error
        if verified_device_id != device_id:
            raise ApiError(403, "Authentik device token does not match the device id")
        existing = self.store.get_registration(device_id)
        if existing and existing["mode"] == "personal" and existing["subject"] != user.subject:
            raise ApiError(403, "personal device is assigned to a different user")
        created = False
        if (
            existing
            and existing.get("push_provider") == "ntfy"
            and existing.get("ntfy_topic")
            and existing.get("subscribe_token")
            and existing.get("publish_token")
            and existing.get("ntfy_reader_username")
            and existing.get("ntfy_writer_username")
        ):
            credentials = NtfyCredentials(
                public_base_url=self.ntfy.public_base_url,
                topic=existing["ntfy_topic"],
                subscribe_token=existing["subscribe_token"],
                publish_token=existing["publish_token"],
                reader_username=existing["ntfy_reader_username"],
                writer_username=existing["ntfy_writer_username"],
            )
        else:
            try:
                credentials = self.ntfy.provision(device_id)
                created = True
            except NtfyError as error:
                raise ApiError(503, str(error)) from error
        try:
            self.store.register_push(
                device_id=device_id,
                subject=user.subject,
                subscribe_token=credentials.subscribe_token,
                publish_token=credentials.publish_token,
                topic=credentials.topic,
                reader_username=credentials.reader_username,
                writer_username=credentials.writer_username,
                agent_token=agent_token,
                key_id=key_id,
                public_jwk=jwk,
                mode=mode,
                privacy=privacy,
                app_version=_text(payload.get("app_version"), 30),
                calendar_reminder_minutes=calendar_reminder_minutes,
                communication_enabled=communication_enabled,
                quiet_hours_enabled=quiet_hours_enabled,
                quiet_start_minutes=quiet_start_minutes,
                quiet_end_minutes=quiet_end_minutes,
                timezone_name=timezone_name,
            )
        except PermissionError as error:
            if created:
                try:
                    self.ntfy.revoke(credentials.reader_username, credentials.writer_username)
                except NtfyError:
                    pass
            raise ApiError(403, str(error)) from error
        except Exception:
            if created:
                try:
                    self.ntfy.revoke(credentials.reader_username, credentials.writer_username)
                except NtfyError:
                    pass
            raise
        return {
            "provider": "ntfy",
            "base_url": credentials.public_base_url,
            "topic": credentials.topic,
            "token": credentials.subscribe_token,
        }

    def register_auth_channel(self, device_id: str, bearer: str, payload: dict[str, Any]) -> None:
        user = self.authenticate(bearer)
        if payload.get("mode") != "personal":
            raise ApiError(403, "login approvals require a personal device")
        key_id = str(payload.get("key_id", ""))
        jwk = payload.get("public_key_jwk")
        agent_token = _text(payload.get("authentik_device_token"), 4096, required=True)
        if not KEY_ID.fullmatch(key_id) or not isinstance(jwk, dict):
            raise ApiError(400, "a valid communication key is required")
        try:
            derived_key_id = device_key_id(jwk)
        except (ValueError, KeyError) as error:
            raise ApiError(400, "the communication public key is invalid") from error
        if derived_key_id != key_id or jwk.get("kid") != key_id:
            raise ApiError(400, "key_id does not match the communication public key")
        try:
            verified_device_id = self.authentik.device_id(agent_token)
        except AuthenticationError as error:
            raise ApiError(403 if error.permanent else 503, str(error)) from error
        if verified_device_id != device_id:
            raise ApiError(403, "Authentik device token does not match the device id")
        try:
            self.store.register_auth_channel(
                device_id=device_id,
                subject=user.subject,
                agent_token=agent_token,
                key_id=key_id,
                public_jwk=jwk,
                mode="personal",
                app_version=_text(payload.get("app_version"), 30),
            )
        except PermissionError as error:
            raise ApiError(403, str(error)) from error

    def unregister_push(self, device_id: str, bearer: str) -> None:
        user = self.authenticate(bearer)
        registration = self.store.get_registration(device_id)
        if registration and registration["subject"] == user.subject:
            self._revoke_ntfy_registration(registration)
        self.store.unregister_push(device_id, user.subject)

    def unregister_auth_channel(self, device_id: str, bearer: str) -> None:
        user = self.authenticate(bearer)
        registration = self.store.get_registration(device_id)
        if registration and registration["subject"] == user.subject:
            self._revoke_ntfy_registration(registration)
        self.store.unregister_auth_channel(device_id, user.subject)

    def link_targets(self, bearer: str, capability: str) -> list[dict[str, Any]]:
        user = self.authenticate(bearer)
        if capability != "open_talk":
            return []
        self._require_capability(user, capability)
        return [dict(target) for target in self.talk_targets]

    def create_handoff(self, bearer: str, payload: dict[str, Any]) -> dict[str, str]:
        user = self.authenticate(bearer)
        if payload.get("action") != "open_talk":
            raise ApiError(400, "only open_talk is supported")
        self._require_capability(user, "open_talk")
        target_id = str(payload.get("target_device_id", ""))
        target = next((item for item in self.talk_targets if item["id"] == target_id), None)
        if target is None or not target["online"]:
            raise ApiError(404, "talk target is unavailable")
        room_token = str(payload.get("room_token", ""))
        if not ROOM_TOKEN.fullmatch(room_token):
            raise ApiError(400, "invalid Talk room token")
        ttl = min(max(int(payload.get("expires_in", 30)), 1), 30)
        handoff_id = self.store.create_handoff(user.subject, None, target_id, room_token, ttl)
        return {"handoff_id": handoff_id, "status": "accepted"}

    def ingest_event(self, source: str, payload: dict[str, Any]) -> dict[str, Any]:
        if source not in {"zimbra", "nextcloud", "test"}:
            raise ApiError(400, "unknown event source")
        event_type = str(payload.get("event_type", ""))
        if event_type not in EVENT_TYPES:
            raise ApiError(400, "invalid event type")
        source_event_id = _text(payload.get("source_event_id"), 200, required=True)
        subject = _text(payload.get("user_subject"), 200, required=True)
        if self.store.user_active_state(subject) is False:
            raise ApiError(403, "user is inactive")
        display_at, display_epoch = _iso_epoch(payload.get("display_at"))
        _, deliver_epoch = _iso_epoch(payload.get("deliver_at"))
        expires_at, expires_epoch = _iso_epoch(payload.get("expires_at"))
        if expires_epoch is None:
            expires_epoch = int(time.time()) + 7 * 86_400
            expires_at = datetime.fromtimestamp(expires_epoch, timezone.utc).isoformat().replace("+00:00", "Z")
        if expires_epoch <= int(time.time()):
            raise ApiError(400, "event is already expired")
        event, created = self.store.put_event(
            {
                "source": source,
                "source_event_id": source_event_id,
                "subject": subject,
                "event_type": event_type,
                "title": _text(payload.get("title"), 80),
                "summary": _text(payload.get("summary"), 160),
                "preview": _text(payload.get("preview"), 280),
                "display_at": display_at,
                "expires_at": expires_at,
                "expires_epoch": expires_epoch,
                "deliver_epoch": (
                    display_epoch - max(CALENDAR_REMINDER_MINUTES) * 60
                    if event_type == "open_calendar" and display_epoch is not None
                    else deliver_epoch or int(time.time())
                ),
            }
        )
        dispatches = 0
        if created:
            registrations = self.store.registrations_for_subject(subject)
            for registration in registrations:
                registration_delivery_epoch = event["deliver_epoch"]
                if event_type == "open_calendar" and display_epoch is not None:
                    registration_delivery_epoch = display_epoch - int(
                        registration.get(
                            "calendar_reminder_minutes",
                            DEFAULT_CALENDAR_REMINDER_MINUTES,
                        )
                    ) * 60
                self.store.queue_delivery(
                    event["event_id"],
                    registration["device_id"],
                    registration_delivery_epoch,
                )
            if registrations:
                dispatches = self.dispatch_due_events(event_id=event["event_id"])
            else:
                self.store.finish_event_without_targets(event["event_id"])
        return {"event_id": event["event_id"], "created": created, "dispatched": dispatches}

    def dispatch_due_events(self, *, event_id: str | None = None) -> int:
        if not self.ntfy.configured:
            return 0
        with self._dispatch_lock:
            dispatches = 0
            for queued in self.store.due_delivery_queue(event_id=event_id):
                queued_event_id = queued["event_id"]
                device_id = queued["device_id"]
                event = self.store.get_event(queued_event_id)
                registration = self.store.get_registration(device_id)
                if event is None or registration is None or registration["subject"] != event["subject"]:
                    self.store.finish_queued_delivery(queued_event_id, device_id, delivered=False)
                    continue
                if event["expires_epoch"] is not None and event["expires_epoch"] <= int(time.time()):
                    self.store.finish_queued_delivery(queued_event_id, device_id, delivered=False)
                    continue
                try:
                    if self.authentik.device_id(registration["agent_token"]) != device_id:
                        self._remove_registration(registration)
                        self.store.finish_event_without_targets(queued_event_id)
                        continue
                    if registration.get("push_provider") != "ntfy":
                        self._remove_registration(registration)
                        self.store.finish_event_without_targets(queued_event_id)
                        continue
                    if not self.communication_allowed(registration):
                        self.store.finish_queued_delivery(
                            queued_event_id,
                            device_id,
                            delivered=False,
                        )
                        continue
                    self.ntfy.send(
                        registration["ntfy_topic"],
                        registration["publish_token"],
                        {
                            "action": "fetch_notification",
                            "event_id": queued_event_id,
                            "event_type": event["event_type"],
                            "revision": str(event["revision"]),
                        },
                    )
                    dispatches += 1
                    self.store.finish_queued_delivery(queued_event_id, device_id, delivered=True)
                except AuthenticationError as error:
                    if error.permanent:
                        self._remove_registration(registration)
                        self.store.finish_event_without_targets(queued_event_id)
                except NtfyError as error:
                    if error.permanent_registration_failure:
                        self._remove_registration(registration)
                        self.store.finish_event_without_targets(queued_event_id)
            return dispatches

    @staticmethod
    def communication_allowed(
        registration: dict[str, Any], *, current_epoch: int | None = None
    ) -> bool:
        if not bool(registration.get("communication_enabled", 1)):
            return False
        if not bool(registration.get("quiet_hours_enabled", 0)):
            return True
        start = int(registration.get("quiet_start_minutes", 22 * 60))
        end = int(registration.get("quiet_end_minutes", 6 * 60))
        if start not in range(1440) or end not in range(1440) or start == end:
            return True
        try:
            local = datetime.fromtimestamp(
                current_epoch if current_epoch is not None else time.time(),
                timezone.utc,
            ).astimezone(ZoneInfo(str(registration.get("timezone") or "Europe/Berlin")))
        except (ValueError, ZoneInfoNotFoundError):
            return True
        current = local.hour * 60 + local.minute
        quiet = start <= current < end if start < end else current >= start or current < end
        return not quiet

    def notification_detail(
        self,
        event_id: str,
        device_id: str,
        key_id: str,
        timestamp: str,
        nonce: str,
        signature: str,
        path: str,
    ) -> dict[str, Any]:
        if not EVENT_ID.fullmatch(event_id):
            raise ApiError(400, "invalid event id")
        self._verify_device_request(
            method="GET",
            path=path,
            device_id=device_id,
            key_id=key_id,
            timestamp=timestamp,
            nonce=nonce,
            signature=signature,
        )
        now = int(time.time())
        resolved = self.store.get_event_for_device(event_id, device_id)
        if resolved is None:
            raise ApiError(404, "notification event was not found")
        event, privacy = resolved
        if event["expires_epoch"] is not None and event["expires_epoch"] <= now:
            raise ApiError(410, "notification event has expired")
        if event["mode"] != "personal" or privacy == "minimal":
            raise ApiError(403, "notification details are disabled on this device")
        self.store.touch_registration(device_id)
        detail = {
            "event_id": event["event_id"],
            "event_type": event["event_type"],
            "title": event["title"],
            "summary": event["summary"],
            "preview": event["preview"] if privacy == "detailed" else "",
            "display_at": event["display_at"],
            "expires_at": event["expires_at"],
            "revision": str(event["revision"]),
        }
        return detail

    def request_login_approval(
        self,
        *,
        subject: str,
        application: str,
        domain: str,
        display_username: str,
        source_ip: str,
        timeout_seconds: int,
    ) -> bool:
        active_registrations = []
        for registration in self.store.auth_registrations_for_subject(subject):
            try:
                if self.authentik.device_id(registration["agent_token"]) == registration["device_id"]:
                    active_registrations.append(registration)
                    self.store.touch_registration(registration["device_id"])
                else:
                    self._remove_registration(registration)
            except AuthenticationError as error:
                if error.permanent:
                    self._remove_registration(registration)
        if not active_registrations:
            return False

        request = self.store.create_auth_request(
            subject=subject,
            application=_text(application, 100),
            domain=_text(domain, 200),
            display_username=_text(display_username, 200),
            source_ip=_text(source_ip, 64),
            ttl=timeout_seconds,
        )
        for registration in active_registrations:
            if (
                not self.ntfy.configured
                or not registration.get("push_enabled")
                or registration.get("push_provider") != "ntfy"
            ):
                continue
            try:
                self.ntfy.send(
                    registration["ntfy_topic"],
                    registration["publish_token"],
                    {
                        "action": "fetch_login_approval",
                        "request_id": request["request_id"],
                    },
                )
            except NtfyError as error:
                if error.permanent_registration_failure:
                    self._remove_registration(registration)

        deadline = time.monotonic() + timeout_seconds
        while time.monotonic() < deadline:
            status = self.store.auth_request_status(request["request_id"])
            if status == "approved":
                return True
            if status in {"denied", "expired", None}:
                return False
            time.sleep(0.2)
        return False

    def _remove_registration(self, registration: dict[str, Any]) -> None:
        self._revoke_ntfy_registration(registration)
        self.store.remove_registration(registration["device_id"])

    def _revoke_ntfy_registration(self, registration: dict[str, Any]) -> None:
        if registration.get("push_provider") != "ntfy" or not self.ntfy.configured:
            return
        try:
            self.ntfy.revoke(
                registration.get("ntfy_reader_username", ""),
                registration.get("ntfy_writer_username", ""),
            )
        except NtfyError:
            # Authentik/device state remains authoritative. Local data must still be removed.
            pass

    def pending_login_approval(
        self,
        *,
        device_id: str,
        key_id: str,
        timestamp: str,
        nonce: str,
        signature: str,
        path: str,
    ) -> dict[str, Any]:
        registration = self._verify_device_request(
            method="GET",
            path=path,
            device_id=device_id,
            key_id=key_id,
            timestamp=timestamp,
            nonce=nonce,
            signature=signature,
        )
        if registration["mode"] != "personal":
            raise ApiError(403, "login approvals require a personal device")
        request = self.store.pending_auth_request_for_device(device_id)
        if request is None:
            return {"request": None}
        return {
            "request": {
                "request_id": request["request_id"],
                "application": request["application"],
                "domain": request["domain"],
                "requested_at": request["created_at"],
                "expires_at": request["expires_at"],
            }
        }

    def decide_login_approval(
        self,
        *,
        request_id: str,
        approved: bool,
        raw_body: bytes,
        device_id: str,
        key_id: str,
        timestamp: str,
        nonce: str,
        signature: str,
        path: str,
    ) -> None:
        if not AUTH_REQUEST_ID.fullmatch(request_id):
            raise ApiError(400, "invalid authentication request id")
        registration = self._verify_device_request(
            method="POST",
            path=path,
            device_id=device_id,
            key_id=key_id,
            timestamp=timestamp,
            nonce=nonce,
            signature=signature,
            body=raw_body,
        )
        if registration["mode"] != "personal":
            raise ApiError(403, "login approvals require a personal device")
        if not self.store.decide_auth_request(request_id, device_id, approved):
            raise ApiError(409, "authentication request is no longer pending")

    def _verify_device_request(
        self,
        *,
        method: str,
        path: str,
        device_id: str,
        key_id: str,
        timestamp: str,
        nonce: str,
        signature: str,
        body: bytes = b"",
    ) -> dict[str, Any]:
        if not 16 <= len(nonce) <= 128:
            raise ApiError(400, "invalid nonce")
        try:
            request_time = int(timestamp)
        except ValueError as error:
            raise ApiError(401, "invalid request timestamp") from error
        now = int(time.time())
        if abs(now - request_time) > 120:
            raise ApiError(401, "request timestamp is outside the allowed window")
        registration = self.store.get_registration(device_id)
        if registration is None or registration["key_id"] != key_id:
            raise ApiError(403, "device identity is unknown")
        canonical = canonical_device_request(
            method, path, device_id, key_id, timestamp, nonce, body
        )
        if not verify_device_signature(registration["public_jwk"], signature, canonical):
            raise ApiError(401, "invalid device signature")
        if not self.store.consume_nonce(device_id, nonce, now + 180):
            raise ApiError(409, "request nonce was already used")
        if now - int(registration["last_verified_at"]) >= 30:
            try:
                verified_device_id = self.authentik.device_id(registration["agent_token"])
            except AuthenticationError as error:
                if error.permanent:
                    self.store.remove_registration(device_id)
                raise ApiError(403 if error.permanent else 503, str(error)) from error
            if verified_device_id != device_id:
                self.store.remove_registration(device_id)
                raise ApiError(403, "Authentik device token does not match the device id")
            self.store.touch_registration(device_id)
        return registration

    @staticmethod
    def _normalize_target(value: dict[str, Any]) -> dict[str, Any]:
        return {
            "id": _text(value.get("id"), 80, required=True),
            "name": _text(value.get("name"), 100, required=True),
            "location": _text(value.get("location"), 100),
            "online": bool(value.get("online", True)),
        }
