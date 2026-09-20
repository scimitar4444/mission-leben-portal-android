from __future__ import annotations

import io
import json
import unittest
import urllib.error
from unittest.mock import patch

from mission_leben_bridge.authentik import AuthenticationError, AuthentikClient


class Response(io.BytesIO):
    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc_value, traceback):
        self.close()


class AuthentikClientTest(unittest.TestCase):
    def test_device_id_uses_central_device_status_endpoint(self) -> None:
        device_uuid = "eeeeeeee-bbbb-cccc-dddd-eeeeeeeeeeee"
        client = AuthentikClient(
            "https://id.example.invalid/application/o/userinfo/",
            "https://geraete.example.invalid/api/v1/devices/status",
        )

        def open_status(request, timeout):
            self.assertEqual(
                "https://geraete.example.invalid/api/v1/devices/status",
                request.full_url,
            )
            self.assertEqual("Bearer+Agent device-secret", request.get_header("Authorization"))
            self.assertEqual(8.0, timeout)
            return Response(json.dumps({"device_id": device_uuid, "trusted": True}).encode())

        with patch("urllib.request.urlopen", side_effect=open_status):
            self.assertEqual(device_uuid, client.device_id("device-secret"))

    def test_disabled_device_is_a_permanent_authentication_error(self) -> None:
        client = AuthentikClient(
            "https://id.example.invalid/application/o/userinfo/",
            "https://geraete.example.invalid/api/v1/devices/status",
        )
        error = urllib.error.HTTPError(
            client.device_status_url,
            403,
            "Forbidden",
            {},
            None,
        )

        with patch("urllib.request.urlopen", side_effect=error):
            with self.assertRaises(AuthenticationError) as caught:
                client.device_id("device-secret")

        self.assertTrue(caught.exception.permanent)
