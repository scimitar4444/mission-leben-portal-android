from __future__ import annotations

import io
import json
import unittest
from unittest.mock import patch

from mission_leben_bridge.nextcloud_talk_participants import NextcloudTalkParticipants


class FakeResponse:
    def __init__(self, payload: object):
        self.buffer = io.BytesIO(json.dumps(payload).encode())

    def __enter__(self):
        return self.buffer

    def __exit__(self, *_: object) -> None:
        self.buffer.close()


def ocs(data: object) -> dict[str, object]:
    return {"ocs": {"meta": {"statuscode": 100}, "data": data}}


class NextcloudTalkParticipantsTest(unittest.TestCase):
    def test_users_and_group_members_are_resolved_and_cached(self) -> None:
        responses = [
            FakeResponse(
                ocs(
                    [
                        {"actorType": "users", "actorId": "user-a"},
                        {"actorType": "groups", "actorId": "care-team"},
                        {"actorType": "guests", "actorId": "guest-a"},
                    ]
                )
            ),
            FakeResponse(ocs({"users": ["user-b", "user-c"]})),
        ]
        client = NextcloudTalkParticipants(
            "https://cloud.example.invalid",
            "bridge-user",
            "app-password",
        )
        with patch(
            "mission_leben_bridge.nextcloud_talk_participants.urllib.request.urlopen",
            side_effect=responses,
        ) as request:
            self.assertEqual({"user-a", "user-b", "user-c"}, client.users("room-token"))
            self.assertEqual({"user-a", "user-b", "user-c"}, client.users("room-token"))
        self.assertEqual(2, request.call_count)


if __name__ == "__main__":
    unittest.main()
