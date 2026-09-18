from __future__ import annotations

import hashlib
import hmac
import json
import re
import time
from datetime import datetime, timedelta, timezone
from typing import Any

from .service import ApiError, BridgeService
from .store import Store


RANDOM_VALUE = re.compile(r"^[A-Za-z0-9+]{32,128}$")
HEX_SIGNATURE = re.compile(r"^[a-fA-F0-9]{64}$")


class NextcloudTalkWebhook:
    def __init__(
        self,
        service: BridgeService,
        store: Store,
        secret: bytes | None,
        expected_backend: str,
        recipients: dict[str, tuple[str, ...]],
        user_subjects: dict[str, str],
    ):
        self.service = service
        self.store = store
        self.secret = secret
        self.expected_backend = expected_backend.rstrip("/")
        self.recipients = recipients
        self.user_subjects = user_subjects

    @property
    def configured(self) -> bool:
        return bool(self.secret and self.expected_backend and self.recipients)

    def receive(self, body: bytes, random_value: str, signature: str, backend: str) -> dict[str, Any]:
        if not self.configured or self.secret is None:
            raise ApiError(503, "Nextcloud Talk adapter is not configured")
        if backend.rstrip("/") != self.expected_backend:
            raise ApiError(401, "unexpected Nextcloud backend")
        if not RANDOM_VALUE.fullmatch(random_value) or not HEX_SIGNATURE.fullmatch(signature):
            raise ApiError(401, "invalid Nextcloud Talk signature headers")
        expected = hmac.new(self.secret, random_value.encode() + body, hashlib.sha256).hexdigest()
        if not hmac.compare_digest(expected, signature.lower()):
            raise ApiError(401, "invalid Nextcloud Talk signature")
        now = int(time.time())
        if not self.store.consume_nonce("nextcloud-talk", random_value, now + 600):
            raise ApiError(409, "Nextcloud Talk webhook was already processed")
        try:
            payload = json.loads(body)
        except json.JSONDecodeError as error:
            raise ApiError(400, "invalid Nextcloud Talk JSON") from error
        if payload.get("type") != "Create":
            return {"accepted": True, "events": 0}
        actor = payload.get("actor") if isinstance(payload.get("actor"), dict) else {}
        note = payload.get("object") if isinstance(payload.get("object"), dict) else {}
        target = payload.get("target") if isinstance(payload.get("target"), dict) else {}
        if note.get("type") != "Note":
            return {"accepted": True, "events": 0}
        room_token = str(target.get("id", ""))
        message_id = str(note.get("id", ""))
        if not room_token or not message_id:
            raise ApiError(400, "Talk webhook has no room or message id")
        recipients = self.recipients.get(room_token, ())
        actor_id = str(actor.get("id", ""))
        nextcloud_user = actor_id.removeprefix("users/") if actor_id.startswith("users/") else ""
        actor_subject = self.user_subjects.get(nextcloud_user, "")
        preview = self._preview(note.get("content"))
        expires_at = (datetime.now(timezone.utc) + timedelta(days=7)).isoformat().replace("+00:00", "Z")
        results = []
        for subject in recipients:
            if subject == actor_subject:
                continue
            results.append(
                self.service.ingest_event(
                    "nextcloud",
                    {
                        "source_event_id": f"talk:{room_token}:{message_id}:{subject}",
                        "user_subject": subject,
                        "event_type": "open_talk",
                        "title": str(actor.get("name") or "Talk"),
                        "summary": str(target.get("name") or "Neue Talk-Nachricht"),
                        "preview": preview,
                        "expires_at": expires_at,
                    },
                )
            )
        return {"accepted": True, "events": len(results)}

    @staticmethod
    def _preview(content: object) -> str:
        if not isinstance(content, str):
            return ""
        try:
            rich = json.loads(content)
        except json.JSONDecodeError:
            return content
        if not isinstance(rich, dict):
            return ""
        message = str(rich.get("message", ""))
        parameters = rich.get("parameters", {})
        if isinstance(parameters, dict):
            for placeholder, value in parameters.items():
                name = value.get("name", "") if isinstance(value, dict) else ""
                message = message.replace("{" + str(placeholder) + "}", str(name))
        return message
