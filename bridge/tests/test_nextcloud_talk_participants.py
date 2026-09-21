from __future__ import annotations

import base64
import io
import hashlib
import hmac
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
    def test_signed_participant_endpoint_is_used_and_cached(self) -> None:
        secret = b"signed-participant-secret-0123456789"
        client = NextcloudTalkParticipants(
            "https://cloud.example.invalid",
            signed_secret=secret,
        )
        response = FakeResponse({"users": ["user-a", "user-b", "user-a"]})
        with patch(
            "mission_leben_bridge.nextcloud_talk_participants.urllib.request.urlopen",
            return_value=response,
        ) as request:
            self.assertEqual({"user-a", "user-b"}, client.users("room-token"))
            self.assertEqual({"user-a", "user-b"}, client.users("room-token"))
        self.assertEqual(1, request.call_count)
        outgoing = request.call_args.args[0]
        self.assertEqual("POST", outgoing.get_method())
        self.assertEqual(
            "https://cloud.example.invalid/apps/missionleben_announcements/api/v1/talk-participants",
            outgoing.full_url,
        )
        body = outgoing.data
        timestamp = outgoing.get_header("X-ml-timestamp")
        nonce = outgoing.get_header("X-ml-nonce")
        canonical = "\n".join(
            ("POST", "/apps/missionleben_announcements/api/v1/talk-participants", timestamp, nonce, hashlib.sha256(body).hexdigest())
        ).encode()
        expected = base64.urlsafe_b64encode(
            hmac.new(secret, canonical, hashlib.sha256).digest()
        ).rstrip(b"=").decode()
        self.assertEqual(expected, outgoing.get_header("X-ml-signature"))

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
