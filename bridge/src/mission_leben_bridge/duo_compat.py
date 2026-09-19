from __future__ import annotations

import base64
import hashlib
import hmac
import json
import time
from dataclasses import dataclass
from email.utils import parsedate_to_datetime
from typing import Mapping
from urllib.parse import parse_qs

from .service import BridgeService


class DuoProtocolError(Exception):
    def __init__(self, status: int, message: str):
        super().__init__(message)
        self.status = status
        self.message = message


@dataclass(frozen=True)
class DuoCompatSettings:
    integration_key: str
    secret_key: bytes
    api_hostname: str
    approval_timeout_seconds: int = 60


class DuoCompatApi:
    """Small, fail-closed subset of Duo Auth API used by authentik 2026.8.3."""

    def __init__(self, settings: DuoCompatSettings, service: BridgeService):
        self.settings = settings
        self.service = service

    def handle(
        self,
        method: str,
        path: str,
        query: str,
        body: bytes,
        headers: Mapping[str, str],
    ) -> tuple[int, dict[str, object]]:
        self._verify_signature(method, path, query, body, headers)

        if method == "GET" and path in {"/auth/v2/ping", "/auth/v2/check"}:
            return 200, self._ok({"time": int(time.time())})
        if method != "POST" or path != "/auth/v2/auth":
            raise DuoProtocolError(404, "Unknown API endpoint")

        try:
            payload = json.loads(body)
        except (UnicodeDecodeError, json.JSONDecodeError) as error:
            raise DuoProtocolError(400, "Invalid JSON body") from error
        if not isinstance(payload, dict):
            raise DuoProtocolError(400, "Invalid JSON body")
        if payload.get("factor") != "auto" or str(payload.get("async", "0")) != "0":
            return 200, self._deny("Unsupported authentication request")

        subject = _safe_text(payload.get("user_id"), 200)
        if not subject:
            return 200, self._deny("User is not registered")

        push_info = parse_qs(str(payload.get("pushinfo", "")), keep_blank_values=True)
        application = _safe_text(push_info.get("Application", [""])[0], 100)
        domain = _safe_text(push_info.get("Domain", [""])[0], 200)
        display_username = _safe_text(payload.get("display_username"), 200)
        source_ip = _safe_text(payload.get("ipaddr"), 64)

        try:
            approved = self.service.request_login_approval(
                subject=subject,
                application=application,
                domain=domain,
                display_username=display_username,
                source_ip=source_ip,
                timeout_seconds=self.settings.approval_timeout_seconds,
            )
        except Exception:
            # Authentik's Duo validation accepts any non-"deny" result. Every
            # internal failure must therefore be converted to an explicit deny.
            approved = False
        if approved:
            return 200, self._ok(
                {
                    "result": "allow",
                    "status": "allow",
                    "status_msg": "Authentication approved.",
                }
            )
        return 200, self._deny("Authentication denied or expired.")

    def _verify_signature(
        self,
        method: str,
        path: str,
        query: str,
        body: bytes,
        headers: Mapping[str, str],
    ) -> None:
        normalized_headers = {name.lower(): value for name, value in headers.items()}
        host = normalized_headers.get("host", "").split(":", 1)[0].lower()
        if host != self.settings.api_hostname.lower():
            raise DuoProtocolError(401, "Invalid API host")

        date = normalized_headers.get("date", "")
        try:
            request_time = int(parsedate_to_datetime(date).timestamp())
        except (TypeError, ValueError, OverflowError) as error:
            raise DuoProtocolError(401, "Invalid request date") from error
        if abs(int(time.time()) - request_time) > 300:
            raise DuoProtocolError(401, "Request date is outside the allowed window")

        authorization = normalized_headers.get("authorization", "")
        if not authorization.startswith("Basic "):
            raise DuoProtocolError(401, "Missing API signature")
        try:
            decoded = base64.b64decode(authorization.removeprefix("Basic "), validate=True).decode(
                "ascii"
            )
            integration_key, supplied_signature = decoded.split(":", 1)
        except (ValueError, UnicodeDecodeError) as error:
            raise DuoProtocolError(401, "Invalid API signature") from error
        if not hmac.compare_digest(integration_key, self.settings.integration_key):
            raise DuoProtocolError(401, "Invalid API signature")

        if query:
            raise DuoProtocolError(400, "Query parameters are not supported")
        duo_headers = {
            name: value
            for name, value in normalized_headers.items()
            if name.startswith("x-duo-")
        }
        canonical_duo_headers = "\x00".join(
            item for pair in sorted(duo_headers.items()) for item in pair
        )
        canonical = "\n".join(
            (
                date,
                method.upper(),
                self.settings.api_hostname.lower(),
                path,
                "",
                hashlib.sha512(body).hexdigest(),
                hashlib.sha512(canonical_duo_headers.encode()).hexdigest(),
            )
        ).encode()
        expected = hmac.new(self.settings.secret_key, canonical, hashlib.sha512).hexdigest()
        if not hmac.compare_digest(expected, supplied_signature):
            raise DuoProtocolError(401, "Invalid API signature")

    @staticmethod
    def _ok(response: object) -> dict[str, object]:
        return {"stat": "OK", "response": response}

    @classmethod
    def _deny(cls, message: str) -> dict[str, object]:
        return cls._ok({"result": "deny", "status": "deny", "status_msg": message})

    @staticmethod
    def failure(message: str) -> dict[str, object]:
        return {"stat": "FAIL", "code": 40103, "message": message}


def _safe_text(value: object, maximum: int) -> str:
    return " ".join(str(value or "").split())[:maximum]
