from __future__ import annotations

import base64
import hashlib
import hmac
import json
import secrets
import time
import urllib.error
import urllib.parse
import urllib.request

from .security import compact_json


TALK_PARTICIPANTS_PATH = "/apps/missionleben_announcements/api/v1/talk-participants"


class TalkParticipantError(RuntimeError):
    pass


class NextcloudTalkParticipants:
    def __init__(
        self,
        base_url: str,
        username: str = "",
        app_password: str = "",
        *,
        signed_secret: bytes | None = None,
        timeout: float = 10.0,
        cache_seconds: int = 60,
    ):
        self.base_url = base_url.rstrip("/")
        self.username = username
        self.app_password = app_password
        self.signed_secret = signed_secret
        self.timeout = timeout
        self.cache_seconds = cache_seconds
        self._cache: dict[str, tuple[float, set[str]]] = {}

    def users(self, room_token: str) -> set[str]:
        cached = self._cache.get(room_token)
        if cached is not None and time.monotonic() - cached[0] < self.cache_seconds:
            return set(cached[1])
        if self.signed_secret is not None:
            users = self._signed_users(room_token)
            self._cache[room_token] = (time.monotonic(), users)
            return set(users)
        data = self._ocs(
            "/ocs/v2.php/apps/spreed/api/v4/room/"
            + urllib.parse.quote(room_token, safe="")
            + "/participants"
        )
        if not isinstance(data, list):
            raise TalkParticipantError("Nextcloud returned no Talk participant list")
        users: set[str] = set()
        groups: set[str] = set()
        for participant in data:
            if not isinstance(participant, dict):
                continue
            actor_type = str(participant.get("actorType", ""))
            actor_id = str(participant.get("actorId", "")).strip()
            if actor_type == "users" and actor_id:
                users.add(actor_id)
            elif actor_type == "groups" and actor_id:
                groups.add(actor_id)
        for group in groups:
            group_data = self._ocs(
                "/ocs/v1.php/cloud/groups/" + urllib.parse.quote(group, safe="")
            )
            if not isinstance(group_data, dict) or not isinstance(group_data.get("users"), list):
                raise TalkParticipantError("Nextcloud returned no group member list")
            users.update(str(value).strip() for value in group_data["users"] if str(value).strip())
        self._cache[room_token] = (time.monotonic(), users)
        return set(users)

    def _signed_users(self, room_token: str) -> set[str]:
        if self.signed_secret is None or len(self.signed_secret) < 32:
            raise TalkParticipantError("Nextcloud Talk participant signing is not configured")
        body = compact_json({"room_token": room_token}).encode()
        timestamp = str(int(time.time()))
        nonce = secrets.token_urlsafe(18)
        canonical = "\n".join(
            ("POST", TALK_PARTICIPANTS_PATH, timestamp, nonce, hashlib.sha256(body).hexdigest())
        ).encode()
        signature = base64.urlsafe_b64encode(
            hmac.new(self.signed_secret, canonical, hashlib.sha256).digest()
        ).rstrip(b"=").decode()
        request = urllib.request.Request(
            self.base_url + TALK_PARTICIPANTS_PATH,
            data=body,
            method="POST",
            headers={
                "Content-Type": "application/json",
                "Accept": "application/json",
                "X-ML-Timestamp": timestamp,
                "X-ML-Nonce": nonce,
                "X-ML-Signature": signature,
                "User-Agent": "mission-leben-talk-bridge/0.2",
            },
        )
        try:
            with urllib.request.urlopen(request, timeout=self.timeout) as response:
                payload = json.load(response)
        except (urllib.error.HTTPError, urllib.error.URLError, ValueError) as error:
            raise TalkParticipantError("Nextcloud Talk participant lookup failed") from error
        values = payload.get("users") if isinstance(payload, dict) else None
        if not isinstance(values, list):
            raise TalkParticipantError("Nextcloud returned no Talk participant list")
        return {str(value).strip() for value in values if str(value).strip()}

    def _ocs(self, path: str):
        credentials = base64.b64encode(
            f"{self.username}:{self.app_password}".encode()
        ).decode("ascii")
        request = urllib.request.Request(
            self.base_url + path + ("&" if "?" in path else "?") + "format=json",
            headers={
                "Accept": "application/json",
                "Authorization": f"Basic {credentials}",
                "OCS-APIRequest": "true",
                "User-Agent": "mission-leben-talk-bridge/0.1",
            },
        )
        try:
            with urllib.request.urlopen(request, timeout=self.timeout) as response:
                payload = json.load(response)
        except (urllib.error.HTTPError, urllib.error.URLError, ValueError) as error:
            raise TalkParticipantError("Nextcloud Talk participant lookup failed") from error
        try:
            meta = payload["ocs"]["meta"]
            data = payload["ocs"]["data"]
        except (KeyError, TypeError) as error:
            raise TalkParticipantError("Nextcloud returned an invalid OCS response") from error
        if int(meta.get("statuscode", 0)) not in {100, 200}:
            raise TalkParticipantError("Nextcloud rejected Talk participant lookup")
        return data
