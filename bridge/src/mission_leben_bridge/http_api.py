from __future__ import annotations

import hmac
import json
import logging
import re
import sqlite3
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any
from urllib.parse import parse_qs, urlsplit

from .authentik import AuthenticationError
from .config import Settings
from .nextcloud_talk import NextcloudTalkWebhook
from .security import verify_source_request
from .service import ApiError, BridgeService


LOGGER = logging.getLogger("mission_leben_bridge.http")
DEVICE_ID = r"([0-9a-fA-F-]{36})"
EVENT_ID = r"([A-Za-z0-9_-]{16,128})"


class BridgeHttpServer(ThreadingHTTPServer):
    daemon_threads = True

    def __init__(self, settings: Settings, service: BridgeService):
        self.settings = settings
        self.service = service
        self.talk_webhook = NextcloudTalkWebhook(
            service,
            service.store,
            settings.nextcloud_talk_secret,
            settings.nextcloud_backend_url,
            settings.talk_recipients,
            settings.nextcloud_user_subjects,
        )
        super().__init__((settings.listen_host, settings.listen_port), BridgeRequestHandler)


class BridgeRequestHandler(BaseHTTPRequestHandler):
    server: BridgeHttpServer
    protocol_version = "HTTP/1.1"

    def do_GET(self) -> None:  # noqa: N802
        self._dispatch("GET")

    def do_POST(self) -> None:  # noqa: N802
        self._dispatch("POST")

    def do_PUT(self) -> None:  # noqa: N802
        self._dispatch("PUT")

    def do_DELETE(self) -> None:  # noqa: N802
        self._dispatch("DELETE")

    def log_message(self, format_string: str, *args: object) -> None:
        LOGGER.info("%s - %s", self.client_address[0], format_string % args)

    def _dispatch(self, method: str) -> None:
        parsed = urlsplit(self.path)
        path = parsed.path
        try:
            if method == "GET" and path == "/healthz":
                self._json(200, {"status": "ok", "fcm_configured": self.server.service.fcm.configured})
                return

            if method == "POST" and path == "/v1/enrollments":
                _, payload = self._body_json()
                self._json(201, self.server.service.enroll(payload))
                return

            match = re.fullmatch(rf"/v1/push/registrations/{DEVICE_ID}", path)
            if match and method == "PUT":
                _, payload = self._body_json()
                self.server.service.register_push(match.group(1), self._bearer(), payload)
                self._empty(204)
                return
            if match and method == "DELETE":
                self.server.service.unregister_push(match.group(1), self._bearer())
                self._empty(204)
                return

            match = re.fullmatch(rf"/v1/devices/{DEVICE_ID}/status", path)
            if match and method == "GET":
                self._json(
                    200,
                    self.server.service.device_status(
                        device_id=match.group(1),
                        key_id=self.headers.get("X-ML-Key-ID", ""),
                        timestamp=self.headers.get("X-ML-Timestamp", ""),
                        nonce=self.headers.get("X-ML-Nonce", ""),
                        signature=self.headers.get("X-ML-Signature", ""),
                        path=path,
                    ),
                )
                return

            if method == "GET" and path == "/v1/link-targets":
                capability = parse_qs(parsed.query).get("capability", [""])[0]
                results = self.server.service.link_targets(self._bearer(), capability)
                self._json(200, {"results": results})
                return

            if method == "POST" and path == "/v1/handoffs":
                _, payload = self._body_json()
                self._json(202, self.server.service.create_handoff(self._bearer(), payload))
                return

            match = re.fullmatch(rf"/v1/notifications/{EVENT_ID}", path)
            if match and method == "GET":
                self._json(
                    200,
                    self.server.service.notification_detail(
                        event_id=match.group(1),
                        device_id=self.headers.get("X-ML-Device-ID", ""),
                        key_id=self.headers.get("X-ML-Key-ID", ""),
                        timestamp=self.headers.get("X-ML-Timestamp", ""),
                        nonce=self.headers.get("X-ML-Nonce", ""),
                        signature=self.headers.get("X-ML-Signature", ""),
                        path=path,
                    ),
                )
                return

            if method == "POST" and path == "/internal/v1/events":
                raw, payload = self._body_json()
                source = self._verify_internal(raw)
                self._json(202, self.server.service.ingest_event(source, payload))
                return

            if method == "POST" and path == "/sources/nextcloud-talk":
                raw, _ = self._body_json()
                self._json(
                    202,
                    self.server.talk_webhook.receive(
                        raw,
                        self.headers.get("X-Nextcloud-Talk-Random", ""),
                        self.headers.get("X-Nextcloud-Talk-Signature", ""),
                        self.headers.get("X-Nextcloud-Talk-Backend", ""),
                    ),
                )
                return

            if method == "POST" and path == "/admin/v1/enrollment-tokens":
                self._require_admin()
                _, payload = self._body_json()
                token = self.server.service.store.create_enrollment_token(
                    mode=str(payload.get("mode", "")),
                    ttl_seconds=int(payload.get("ttl_seconds", 900)),
                    auto_trust=bool(payload.get("auto_trust", False)),
                )
                self._json(201, {"enrollment_token": token})
                return

            if method == "GET" and path == "/admin/v1/devices":
                self._require_admin()
                self._json(200, {"results": self.server.service.store.list_devices()})
                return

            match = re.fullmatch(rf"/admin/v1/devices/{DEVICE_ID}/status", path)
            if match and method == "POST":
                self._require_admin()
                _, payload = self._body_json()
                if not self.server.service.store.set_device_status(match.group(1), str(payload.get("status", ""))):
                    raise ApiError(404, "device was not found")
                self._empty(204)
                return

            match = re.fullmatch(r"/internal/v1/users/([^/]+)/status", path)
            if match and method == "POST":
                raw, payload = self._body_json()
                self._verify_internal(raw)
                subject = match.group(1)
                if not self.server.service.store.set_user_active(subject, bool(payload.get("active", False))):
                    raise ApiError(404, "user was not found")
                self._empty(204)
                return

            raise ApiError(404, "endpoint was not found")
        except ApiError as error:
            self._json(error.status, {"error": error.message})
        except AuthenticationError:
            self._json(401, {"error": "authentication failed"})
        except (ValueError, TypeError, json.JSONDecodeError) as error:
            self._json(400, {"error": str(error) or "invalid request"})
        except sqlite3.IntegrityError:
            self._json(409, {"error": "request conflicts with existing data"})
        except Exception:
            LOGGER.exception("unhandled bridge request error")
            self._json(500, {"error": "internal server error"})

    def _body_json(self) -> tuple[bytes, dict[str, Any]]:
        try:
            length = int(self.headers.get("Content-Length", "0"))
        except ValueError as error:
            raise ApiError(400, "invalid content length") from error
        if length < 0 or length > 65_536:
            raise ApiError(413, "request body is too large")
        raw = self.rfile.read(length)
        value = json.loads(raw or b"{}")
        if not isinstance(value, dict):
            raise ApiError(400, "request body must be a JSON object")
        return raw, value

    def _bearer(self) -> str:
        header = self.headers.get("Authorization", "")
        if not header.startswith("Bearer "):
            raise ApiError(401, "bearer token is required")
        return header.removeprefix("Bearer ").strip()

    def _require_admin(self) -> None:
        supplied = self.headers.get("X-ML-Admin-Key", "")
        if not hmac.compare_digest(supplied, self.server.settings.admin_api_key):
            raise ApiError(403, "admin access denied")

    def _verify_internal(self, body: bytes) -> str:
        source = self.headers.get("X-ML-Source", "")
        if not verify_source_request(
            self.server.settings.internal_hmac_secret,
            source,
            self.headers.get("X-ML-Timestamp", ""),
            body,
            self.headers.get("X-ML-Signature", ""),
        ):
            raise ApiError(401, "invalid source signature")
        return source

    def _json(self, status: int, payload: dict[str, Any]) -> None:
        body = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.end_headers()
        self.wfile.write(body)

    def _empty(self, status: int) -> None:
        self.send_response(status)
        self.send_header("Content-Length", "0")
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
