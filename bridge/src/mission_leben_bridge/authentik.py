from __future__ import annotations

import json
import urllib.error
import urllib.request
from dataclasses import dataclass


class AuthenticationError(Exception):
    pass


@dataclass(frozen=True)
class UserInfo:
    subject: str
    email: str
    display_name: str


class AuthentikClient:
    def __init__(self, userinfo_url: str, timeout: float = 8.0):
        self.userinfo_url = userinfo_url
        self.timeout = timeout

    def user_info(self, access_token: str) -> UserInfo:
        if not access_token:
            raise AuthenticationError("missing bearer token")
        request = urllib.request.Request(
            self.userinfo_url,
            headers={
                "Authorization": f"Bearer {access_token}",
                "Accept": "application/json",
                "User-Agent": "mission-leben-bridge/0.4",
            },
        )
        try:
            with urllib.request.urlopen(request, timeout=self.timeout) as response:
                payload = json.load(response)
        except (urllib.error.HTTPError, urllib.error.URLError, ValueError) as error:
            raise AuthenticationError("Authentik access token was rejected") from error
        subject = str(payload.get("sub", "")).strip()
        if not subject:
            raise AuthenticationError("Authentik userinfo contains no subject")
        email = str(payload.get("email") or payload.get("preferred_username") or "").strip()
        name = str(payload.get("name") or payload.get("preferred_username") or email or subject).strip()
        return UserInfo(subject=subject, email=email, display_name=name)
