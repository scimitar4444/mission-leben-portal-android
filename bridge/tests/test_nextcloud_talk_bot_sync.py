from __future__ import annotations

import base64
import hashlib
import hmac
import io
import json
import unittest
from unittest.mock import patch

from mission_leben_bridge.nextcloud_talk_bot_sync import (
    NextcloudTalkBotSync,
    TALK_BOT_SYNC_PATH,
    TalkBotSyncError,
    _user_ids,
)


class FakeResponse:
    def __init__(self, payload: object):
        self.buffer = io.BytesIO(json.dumps(payload).encode())

    def __enter__(self):
        return self.buffer

    def __exit__(self, *_: object) -> None:
        self.buffer.close()


class NextcloudTalkBotSyncTest(unittest.TestCase):
    def test_signed_room_sync_sends_sorted_unique_user_ids(self) -> None:
        secret = b"signed-talk-room-sync-secret-0123456789"
        client = NextcloudTalkBotSync("https://cloud.example.invalid/", secret)
        response = FakeResponse(
            {
                "eligible_users": 2,
                "matched_users": 2,
                "desired_rooms": 7,
                "added": 6,
                "removed": 0,
            }
        )
        with patch(
            "mission_leben_bridge.nextcloud_talk_bot_sync.urllib.request.urlopen",
            return_value=response,
        ) as request:
            result = client.sync({"user-b", " user-a ", "user-a"})

        self.assertEqual(6, result["added"])
        outgoing = request.call_args.args[0]
        self.assertEqual("POST", outgoing.get_method())
        self.assertEqual(
            "https://cloud.example.invalid" + TALK_BOT_SYNC_PATH,
            outgoing.full_url,
        )
        self.assertEqual({"user_ids": ["user-a", "user-b"]}, json.loads(outgoing.data))
        timestamp = outgoing.get_header("X-ml-timestamp")
        nonce = outgoing.get_header("X-ml-nonce")
        canonical = "\n".join(
            ("POST", TALK_BOT_SYNC_PATH, timestamp, nonce, hashlib.sha256(outgoing.data).hexdigest())
        ).encode()
        expected = base64.urlsafe_b64encode(
            hmac.new(secret, canonical, hashlib.sha256).digest()
        ).rstrip(b"=").decode()
        self.assertEqual(expected, outgoing.get_header("X-ml-signature"))

    def test_assignment_filter_uses_only_talk_enabled_accounts(self) -> None:
        payload = {
            "assignments": [
                {"talk": True, "nextcloud_user_id": "user-a"},
                {"talk": False, "nextcloud_user_id": "user-b"},
                {"zimbra": True, "nextcloud_user_id": "user-c"},
            ]
        }
        self.assertEqual({"user-a"}, _user_ids(payload))

    def test_missing_user_id_in_talk_assignment_is_rejected(self) -> None:
        with self.assertRaises(TalkBotSyncError):
            _user_ids({"assignments": [{"talk": True}]})

    def test_invalid_response_is_rejected(self) -> None:
        client = NextcloudTalkBotSync(
            "https://cloud.example.invalid",
            b"signed-talk-room-sync-secret-0123456789",
        )
        with patch(
            "mission_leben_bridge.nextcloud_talk_bot_sync.urllib.request.urlopen",
            return_value=FakeResponse({"added": 1}),
        ), self.assertRaises(TalkBotSyncError):
            client.sync({"user-a"})


if __name__ == "__main__":
    unittest.main()
