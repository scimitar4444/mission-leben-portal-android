from __future__ import annotations

import base64
import hashlib
import hmac
import json
import secrets
import time
import urllib.error
import urllib.request
from typing import Any

from .security import compact_json


ANNOUNCEMENTS_PATH = "/apps/missionleben_announcements/api/v1/announcements"
PUSH_PATH = "/apps/missionleben_announcements/api/v1/announcement-push/"
HISTORY_PATH = "/apps/missionleben_announcements/api/v1/announcement-history"


class AnnouncementFetchError(Exception):
    pass


class NextcloudAnnouncementClient:
    def __init__(self, base_url: str, secret: bytes, timeout: float = 8.0):
        self.base_url = base_url.rstrip("/")
        self.secret = secret
        self.timeout = timeout

    @property
    def configured(self) -> bool:
        return self.base_url.startswith("https://") and len(self.secret) >= 32

    def fetch(self, *, user_id: str = "", email: str = "") -> list[dict[str, Any]]:
        payload = self._post(ANNOUNCEMENTS_PATH, {"user_id": user_id, "email": email})
        values = payload.get("announcements", [])
        if not isinstance(values, list):
            raise AnnouncementFetchError("Nextcloud announcement response is invalid")
        return [value for value in values[:7] if isinstance(value, dict)]

    def catalog(self) -> list[int]:
        values = self._post(PUSH_PATH + "catalog", {}).get("ids")
        if not isinstance(values, list) or len(values) > 500 or any(type(v) is not int or v < 1 for v in values):
            raise AnnouncementFetchError("Nextcloud announcement catalogue is invalid")
        return sorted(set(values))

    def history(self, *, user_id: str = "", email: str = "") -> list[dict[str, Any]]:
        payload = self._post(HISTORY_PATH, {"user_id": user_id, "email": email})
        values = payload.get("announcements")
        if payload.get("retention_days") != 14 or not isinstance(values, list) or len(values) > 500:
            raise AnnouncementFetchError("Invalid announcement history")
        seen: set[int] = set()
        for item in values:
            if not isinstance(item, dict) or type(item.get("id")) is not int or item["id"] < 1 \
                    or item["id"] in seen or type(item.get("archived")) is not bool:
                raise AnnouncementFetchError("Invalid announcement history item")
            seen.add(item["id"])
        return values

    def resolve(self, ids: list[int], recipients: list[dict[str, str]]) -> dict[str, list[dict[str, Any]]]:
        if not 1 <= len(ids) <= 100 or not 1 <= len(recipients) <= 100:
            raise AnnouncementFetchError("Invalid announcement batch size")
        payload = self._post(PUSH_PATH + "resolve", {"ids": ids, "recipients": recipients})
        values = payload.get("recipients")
        expected = {r["key"] for r in recipients}
        result: dict[str, list[dict[str, Any]]] = {}
        if not isinstance(values, list):
            raise AnnouncementFetchError("Nextcloud announcement recipients are invalid")
        for value in values:
            if not isinstance(value, dict) or not isinstance(value.get('key'), str) or value['key'] not in expected or value["key"] in result:
                raise AnnouncementFetchError("Unexpected announcement recipient")
            items = value.get("announcements")
            if not isinstance(items, list) or any(not isinstance(v, dict) or type(v.get("id")) is not int or v["id"] not in ids for v in items):
                raise AnnouncementFetchError("Unexpected announcement result")
            result[value["key"]] = items
        if set(result) != expected:
            raise AnnouncementFetchError("Incomplete announcement recipients")
        return result

    def _post(self, path: str, payload: dict[str, Any]) -> dict[str, Any]:
        if not self.configured:
            raise AnnouncementFetchError("Nextcloud announcements are not configured")
        body = compact_json(payload).encode()
        timestamp = str(int(time.time()))
        nonce = secrets.token_urlsafe(18)
        canonical = "\n".join(
            ("POST", path, timestamp, nonce, hashlib.sha256(body).hexdigest())
        ).encode()
        signature = base64.urlsafe_b64encode(
            hmac.new(self.secret, canonical, hashlib.sha256).digest()
        ).rstrip(b"=").decode()
        request = urllib.request.Request(
            self.base_url + path,
            data=body,
            method="POST",
            headers={
                "Content-Type": "application/json",
                "Accept": "application/json",
                "X-ML-Timestamp": timestamp,
                "X-ML-Nonce": nonce,
                "X-ML-Signature": signature,
                "User-Agent": "mission-leben-bridge/0.11",
            },
        )
        try:
            with urllib.request.urlopen(request, timeout=self.timeout) as response:
                payload = json.loads(response.read(2_000_001))
        except urllib.error.HTTPError as error:
            error.close()
            raise AnnouncementFetchError("Nextcloud announcements are unavailable") from error
        except (urllib.error.URLError, OSError, ValueError) as error:
            raise AnnouncementFetchError("Nextcloud announcements are unavailable") from error
        if not isinstance(payload, dict):
            raise AnnouncementFetchError("Nextcloud announcement response is invalid")
        return payload
