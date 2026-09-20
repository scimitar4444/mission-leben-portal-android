from __future__ import annotations

import io
import json
import urllib.error
from unittest.mock import patch

import pytest

from mission_leben_bridge.authentik import AuthenticationError, AuthentikClient


class Response(io.BytesIO):
    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc_value, traceback):
        self.close()


def test_device_id_uses_central_device_status_endpoint() -> None:
    device_uuid = "eeeeeeee-bbbb-cccc-dddd-eeeeeeeeeeee"
    client = AuthentikClient(
        "https://id.example.invalid/application/o/userinfo/",
        "https://geraete.example.invalid/api/v1/devices/status",
    )

    def open_status(request, timeout):
        assert request.full_url == "https://geraete.example.invalid/api/v1/devices/status"
        assert request.get_header("Authorization") == "Bearer+Agent device-secret"
        assert timeout == 8.0
        return Response(json.dumps({"device_id": device_uuid, "trusted": True}).encode())

    with patch("urllib.request.urlopen", side_effect=open_status):
        assert client.device_id("device-secret") == device_uuid


def test_disabled_device_is_a_permanent_authentication_error() -> None:
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
        with pytest.raises(AuthenticationError) as caught:
            client.device_id("device-secret")

    assert caught.value.permanent is True
