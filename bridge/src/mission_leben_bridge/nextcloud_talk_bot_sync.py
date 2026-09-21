from __future__ import annotations

import base64
import hashlib
import hmac
import json
import os
from pathlib import Path
import secrets
import sys
import time
import urllib.error
import urllib.request
from typing import Any

from .security import compact_json


TALK_BOT_SYNC_PATH = "/apps/missionleben_announcements/api/v1/talk-bot-sync"


class TalkBotSyncError(RuntimeError):
    pass


class NextcloudTalkBotSync:
    def __init__(self, base_url: str, signed_secret: bytes, *, timeout: float = 15.0):
        self.base_url = base_url.rstrip("/")
        self.signed_secret = signed_secret
        self.timeout = timeout

    def sync(self, user_ids: set[str]) -> dict[str, int]:
        if not self.base_url.startswith("https://"):
            raise TalkBotSyncError("Nextcloud Talk bot sync requires HTTPS")
        if len(self.signed_secret) < 32:
            raise TalkBotSyncError("Nextcloud Talk bot sync signing is not configured")
        values = sorted({value.strip() for value in user_ids if value.strip()})
        if len(values) > 5000:
            raise TalkBotSyncError("Nextcloud Talk bot sync has too many users")

        body = compact_json({"user_ids": values}).encode()
        timestamp = str(int(time.time()))
        nonce = secrets.token_urlsafe(18)
        canonical = "\n".join(
            ("POST", TALK_BOT_SYNC_PATH, timestamp, nonce, hashlib.sha256(body).hexdigest())
        ).encode()
        signature = base64.urlsafe_b64encode(
            hmac.new(self.signed_secret, canonical, hashlib.sha256).digest()
        ).rstrip(b"=").decode()
        request = urllib.request.Request(
            self.base_url + TALK_BOT_SYNC_PATH,
            data=body,
            method="POST",
            headers={
                "Content-Type": "application/json",
                "Accept": "application/json",
                "X-ML-Timestamp": timestamp,
                "X-ML-Nonce": nonce,
                "X-ML-Signature": signature,
                "User-Agent": "mission-leben-talk-room-sync/0.1",
            },
        )
        try:
            with urllib.request.urlopen(request, timeout=self.timeout) as response:
                payload: Any = json.load(response)
        except (urllib.error.HTTPError, urllib.error.URLError, ValueError) as error:
            raise TalkBotSyncError("Nextcloud Talk bot room sync failed") from error
        required = {"eligible_users", "matched_users", "desired_rooms", "added", "removed"}
        if not isinstance(payload, dict) or not required.issubset(payload):
            raise TalkBotSyncError("Nextcloud returned an invalid Talk bot sync response")
        try:
            result = {key: int(payload[key]) for key in sorted(required)}
        except (TypeError, ValueError) as error:
            raise TalkBotSyncError("Nextcloud returned an invalid Talk bot sync response") from error
        if any(value < 0 for value in result.values()):
            raise TalkBotSyncError("Nextcloud returned an invalid Talk bot sync response")
        return result


def _secret_from_environment() -> bytes:
    value = os.getenv("BRIDGE_NEXTCLOUD_ANNOUNCEMENTS_SECRET", "").strip()
    path = os.getenv("BRIDGE_NEXTCLOUD_ANNOUNCEMENTS_SECRET_FILE", "").strip()
    if value and path:
        raise TalkBotSyncError("Nextcloud sync secret is configured twice")
    if path:
        try:
            value = Path(path).read_text(encoding="utf-8").strip()
        except OSError as error:
            raise TalkBotSyncError("Nextcloud sync secret is unavailable") from error
    if len(value) < 32:
        raise TalkBotSyncError("Nextcloud sync secret is not configured")
    return value.encode()


def _user_ids(payload: Any) -> set[str]:
    assignments = payload.get("assignments") if isinstance(payload, dict) else None
    if not isinstance(assignments, list):
        raise TalkBotSyncError("Communication assignments are invalid")
    user_ids: set[str] = set()
    for assignment in assignments:
        if not isinstance(assignment, dict) or not bool(assignment.get("talk")):
            continue
        user_id = str(assignment.get("nextcloud_user_id", "")).strip()
        if not user_id:
            raise TalkBotSyncError("Talk assignment has no Nextcloud user id")
        user_ids.add(user_id)
    return user_ids


def main() -> None:
    try:
        payload = json.load(sys.stdin)
        user_ids = _user_ids(payload)
        base_url = os.getenv("BRIDGE_NEXTCLOUD_ANNOUNCEMENTS_URL", "").strip()
        result = NextcloudTalkBotSync(base_url, _secret_from_environment()).sync(user_ids)
    except (json.JSONDecodeError, TalkBotSyncError) as error:
        print(f"ML_TALK_BOT_SYNC_STATUS=failed reason={error}", file=sys.stderr)
        raise SystemExit(1) from error
    print("ML_TALK_BOT_SYNC_STATUS=" + compact_json(result))


if __name__ == "__main__":
    main()
