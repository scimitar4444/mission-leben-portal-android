from __future__ import annotations

import json
import re
import time
from datetime import datetime, timezone
from typing import Any

from .authentik import AuthentikClient, UserInfo
from .fcm import FcmSendError, FcmSender, NullFcmSender
from .security import canonical_device_request, device_key_id, verify_device_signature
from .store import Store


EVENT_ID = re.compile(r"^[A-Za-z0-9_-]{16,128}$")
KEY_ID = re.compile(r"^[a-f0-9]{24}$")
ROOM_TOKEN = re.compile(r"^[A-Za-z0-9_-]{6,128}$")
EVENT_TYPES = {"open_mail", "open_calendar", "open_talk"}
PRIVACY_LEVELS = {"minimal", "standard", "detailed"}


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


class BridgeService:
    def __init__(
        self,
        store: Store,
        authentik: AuthentikClient,
        fcm: FcmSender | NullFcmSender,
        talk_targets: tuple[dict[str, Any], ...],
    ):
        self.store = store
        self.authentik = authentik
        self.fcm = fcm
        self.talk_targets = tuple(self._normalize_target(target) for target in talk_targets)

    def authenticate(self, bearer: str) -> UserInfo:
        user = self.authentik.user_info(bearer)
        self.store.upsert_user(user.subject, user.email, user.display_name, active=True)
        return user

    def enroll(self, payload: dict[str, Any]) -> dict[str, Any]:
        mode = str(payload.get("mode", ""))
        key_id = str(payload.get("key_id", ""))
        jwk = payload.get("public_key_jwk")
        if mode not in {"personal", "shared"}:
            raise ApiError(400, "mode must be personal or shared")
        if not KEY_ID.fullmatch(key_id) or not isinstance(jwk, dict):
            raise ApiError(400, "a valid device key is required")
        try:
            derived_key_id = device_key_id(jwk)
        except (ValueError, KeyError) as error:
            raise ApiError(400, "the device public key is invalid") from error
        if derived_key_id != key_id or jwk.get("kid") != key_id:
            raise ApiError(400, "key_id does not match the public key")
        normalized = {
            "mode": mode,
            "key_id": key_id,
            "public_key_jwk": jwk,
            "device_name": _text(payload.get("device_name"), 100, required=True),
            "platform": _text(payload.get("platform"), 30, required=True),
            "os_version": _text(payload.get("os_version"), 30, required=True),
            "app_version": _text(payload.get("app_version"), 30, required=True),
        }
        try:
            return self.store.enroll_device(str(payload.get("enrollment_token", "")), normalized)
        except ValueError as error:
            raise ApiError(400, str(error)) from error

    def register_push(self, device_id: str, bearer: str, payload: dict[str, Any]) -> None:
        user = self.authenticate(bearer)
        privacy = str(payload.get("notification_privacy", "standard"))
        if privacy not in PRIVACY_LEVELS:
            raise ApiError(400, "invalid notification privacy level")
        if payload.get("provider") != "fcm":
            raise ApiError(400, "only the fcm provider is supported")
        installation_id = _text(payload.get("installation_id"), 4096, required=True)
        try:
            self.store.register_push(
                device_id=device_id,
                subject=user.subject,
                installation_id=installation_id,
                privacy=privacy,
                app_version=_text(payload.get("app_version"), 30),
            )
        except PermissionError as error:
            raise ApiError(403, str(error)) from error

    def unregister_push(self, device_id: str, bearer: str) -> None:
        user = self.authenticate(bearer)
        self.store.unregister_push(device_id, user.subject)

    def link_targets(self, bearer: str, capability: str) -> list[dict[str, Any]]:
        self.authenticate(bearer)
        if capability != "open_talk":
            return []
        return [dict(target) for target in self.talk_targets]

    def create_handoff(self, bearer: str, payload: dict[str, Any]) -> dict[str, str]:
        user = self.authenticate(bearer)
        if payload.get("action") != "open_talk":
            raise ApiError(400, "only open_talk is supported")
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
        display_at, _ = _iso_epoch(payload.get("display_at"))
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
                "deliver_epoch": deliver_epoch or int(time.time()),
            }
        )
        dispatches = 0
        if created and event["deliver_epoch"] <= int(time.time()):
            dispatches, complete = self._dispatch_event(event)
            if complete:
                self.store.mark_event_delivered(event["event_id"])
        return {"event_id": event["event_id"], "created": created, "dispatched": dispatches}

    def dispatch_due_events(self) -> int:
        dispatched = 0
        for event in self.store.due_events():
            count, complete = self._dispatch_event(event)
            dispatched += count
            if complete:
                self.store.mark_event_delivered(event["event_id"])
        return dispatched

    def _dispatch_event(self, event: dict[str, Any]) -> tuple[int, bool]:
        dispatches = 0
        complete = True
        for registration in self.store.registrations_for_subject(event["subject"]):
            if self.store.delivery_exists(event["event_id"], registration["device_id"]):
                continue
            try:
                self.fcm.send(
                    registration["installation_id"],
                    {
                        "action": "fetch_notification",
                        "event_id": event["event_id"],
                        "event_type": event["event_type"],
                        "revision": str(event["revision"]),
                    },
                )
                dispatches += int(self.fcm.configured)
                self.store.record_delivery(event["event_id"], registration["device_id"])
            except FcmSendError as error:
                if error.permanent_token_failure:
                    self.store.remove_registration_by_token(registration["installation_id"])
                else:
                    complete = False
        return dispatches, complete

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
            require_trusted=True,
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
        self.store.touch_device(device_id)
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

    def device_status(
        self,
        device_id: str,
        key_id: str,
        timestamp: str,
        nonce: str,
        signature: str,
        path: str,
    ) -> dict[str, str]:
        device = self._verify_device_request(
            method="GET",
            path=path,
            device_id=device_id,
            key_id=key_id,
            timestamp=timestamp,
            nonce=nonce,
            signature=signature,
            require_trusted=False,
        )
        self.store.touch_device(device_id)
        return {"device_id": device_id, "status": device["status"]}

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
        require_trusted: bool,
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
        device = self.store.get_device(device_id)
        if device is None or device["key_id"] != key_id:
            raise ApiError(403, "device identity is unknown")
        if require_trusted and device["status"] != "trusted":
            raise ApiError(403, "device is not trusted")
        canonical = canonical_device_request(method, path, device_id, key_id, timestamp, nonce)
        if not verify_device_signature(device["public_jwk"], signature, canonical):
            raise ApiError(401, "invalid device signature")
        if not self.store.consume_nonce(device_id, nonce, now + 180):
            raise ApiError(409, "request nonce was already used")
        return device

    @staticmethod
    def _normalize_target(value: dict[str, Any]) -> dict[str, Any]:
        return {
            "id": _text(value.get("id"), 80, required=True),
            "name": _text(value.get("name"), 100, required=True),
            "location": _text(value.get("location"), 100),
            "online": bool(value.get("online", True)),
        }
