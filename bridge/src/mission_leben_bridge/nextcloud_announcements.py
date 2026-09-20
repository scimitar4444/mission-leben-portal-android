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
        if not self.configured:
            raise AnnouncementFetchError("Nextcloud announcements are not configured")
        body = compact_json({"user_id": user_id, "email": email}).encode()
        timestamp = str(int(time.time()))
        nonce = secrets.token_urlsafe(18)
        canonical = "\n".join(
            ("POST", ANNOUNCEMENTS_PATH, timestamp, nonce, hashlib.sha256(body).hexdigest())
        ).encode()
        signature = base64.urlsafe_b64encode(
            hmac.new(self.secret, canonical, hashlib.sha256).digest()
        ).rstrip(b"=").decode()
        request = urllib.request.Request(
            self.base_url + ANNOUNCEMENTS_PATH,
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
                payload = json.load(response)
        except (urllib.error.HTTPError, urllib.error.URLError, ValueError) as error:
            raise AnnouncementFetchError("Nextcloud announcements are unavailable") from error
        values = payload.get("announcements", []) if isinstance(payload, dict) else []
        if not isinstance(values, list):
            raise AnnouncementFetchError("Nextcloud announcement response is invalid")
        return [value for value in values[:7] if isinstance(value, dict)]
