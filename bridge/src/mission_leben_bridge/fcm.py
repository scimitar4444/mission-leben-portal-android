from __future__ import annotations

import json
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding

from .security import base64url_encode, compact_json


class FcmSendError(Exception):
    def __init__(self, message: str, permanent_token_failure: bool = False):
        super().__init__(message)
        self.permanent_token_failure = permanent_token_failure


class NullFcmSender:
    configured = False

    def send(self, installation_id: str, data: dict[str, str]) -> None:
        return None


@dataclass
class ServiceAccountTokenProvider:
    credentials_path: Path
    _token: str = ""
    _expires_at: int = 0

    def __post_init__(self) -> None:
        self._lock = threading.Lock()
        with self.credentials_path.open(encoding="utf-8") as handle:
            self._credentials: dict[str, Any] = json.load(handle)
        self._private_key = serialization.load_pem_private_key(
            self._credentials["private_key"].encode(),
            password=None,
        )

    def token(self) -> str:
        with self._lock:
            now = int(time.time())
            if self._token and self._expires_at - now > 60:
                return self._token
            assertion = self._assertion(now)
            body = urllib.parse.urlencode(
                {
                    "grant_type": "urn:ietf:params:oauth:grant-type:jwt-bearer",
                    "assertion": assertion,
                }
            ).encode()
            request = urllib.request.Request(
                self._credentials.get("token_uri", "https://oauth2.googleapis.com/token"),
                data=body,
                headers={"Content-Type": "application/x-www-form-urlencoded"},
                method="POST",
            )
            with urllib.request.urlopen(request, timeout=10) as response:
                result = json.load(response)
            self._token = result["access_token"]
            self._expires_at = now + int(result.get("expires_in", 3600))
            return self._token

    def _assertion(self, now: int) -> str:
        header = base64url_encode(compact_json({"alg": "RS256", "typ": "JWT"}).encode())
        claims = base64url_encode(
            compact_json(
                {
                    "iss": self._credentials["client_email"],
                    "scope": "https://www.googleapis.com/auth/firebase.messaging",
                    "aud": self._credentials.get("token_uri", "https://oauth2.googleapis.com/token"),
                    "iat": now,
                    "exp": now + 3600,
                }
            ).encode()
        )
        signing_input = f"{header}.{claims}".encode()
        signature = self._private_key.sign(signing_input, padding.PKCS1v15(), hashes.SHA256())
        return f"{header}.{claims}.{base64url_encode(signature)}"


class FcmSender:
    configured = True

    def __init__(self, project_id: str, credentials_path: Path):
        self.project_id = project_id
        self.tokens = ServiceAccountTokenProvider(credentials_path)

    def send(self, installation_id: str, data: dict[str, str]) -> None:
        endpoint = f"https://fcm.googleapis.com/v1/projects/{urllib.parse.quote(self.project_id)}/messages:send"
        body = compact_json(
            {
                "message": {
                    "token": installation_id,
                    "data": data,
                    "android": {
                        "priority": "HIGH",
                        "ttl": "300s",
                    },
                }
            }
        ).encode()
        request = urllib.request.Request(
            endpoint,
            data=body,
            headers={
                "Authorization": f"Bearer {self.tokens.token()}",
                "Content-Type": "application/json; charset=utf-8",
            },
            method="POST",
        )
        try:
            with urllib.request.urlopen(request, timeout=12) as response:
                if response.status not in range(200, 300):
                    raise FcmSendError(f"FCM returned HTTP {response.status}")
        except urllib.error.HTTPError as error:
            response_body = error.read(16_384).decode(errors="replace")
            permanent = error.code in {400, 404} and any(
                marker in response_body for marker in ("UNREGISTERED", "registration-token-not-registered")
            )
            raise FcmSendError(f"FCM returned HTTP {error.code}", permanent_token_failure=permanent) from error
        except urllib.error.URLError as error:
            raise FcmSendError("FCM is temporarily unreachable") from error
