from __future__ import annotations

import base64
import json
import time
import urllib.error
import urllib.parse
import urllib.request


class TalkParticipantError(RuntimeError):
    pass


class NextcloudTalkParticipants:
    def __init__(
        self,
        base_url: str,
        username: str,
        app_password: str,
        *,
        timeout: float = 10.0,
        cache_seconds: int = 60,
    ):
        self.base_url = base_url.rstrip("/")
        self.username = username
        self.app_password = app_password
        self.timeout = timeout
        self.cache_seconds = cache_seconds
        self._cache: dict[str, tuple[float, set[str]]] = {}

    def users(self, room_token: str) -> set[str]:
        cached = self._cache.get(room_token)
        if cached is not None and time.monotonic() - cached[0] < self.cache_seconds:
            return set(cached[1])
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
