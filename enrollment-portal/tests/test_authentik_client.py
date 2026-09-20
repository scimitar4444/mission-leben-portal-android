from __future__ import annotations

import json
from datetime import UTC, datetime

import httpx
import pytest

from mission_leben_device_enrollment.authentik import AuthentikClient


@pytest.mark.asyncio
async def test_agent_device_id_uses_authentik_agent_scheme(settings):
    device_uuid = "eeeeeeee-bbbb-cccc-dddd-eeeeeeeeeeee"

    def handler(request: httpx.Request) -> httpx.Response:
        assert request.url.path == "/api/v3/endpoints/agents/connectors/agent_config/"
        assert request.headers["authorization"] == "Bearer+Agent device-secret"
        return httpx.Response(200, json={"device_id": device_uuid})

    client = AuthentikClient(settings, httpx.MockTransport(handler))
    try:
        assert await client.agent_device_id("device-secret") == device_uuid
    finally:
        await client.close()


@pytest.mark.asyncio
async def test_disable_device_preserves_record_and_existing_attributes(settings):
    device_uuid = "eeeeeeee-bbbb-cccc-dddd-eeeeeeeeeeee"
    disabled_at = datetime(2026, 9, 20, 13, 45, tzinfo=UTC)
    calls = []

    def handler(request: httpx.Request) -> httpx.Response:
        calls.append((request.method, request.url.path))
        if request.method == "GET":
            return httpx.Response(
                200,
                json={
                    "device_uuid": device_uuid,
                    "attributes": {"serial": "ml-android-1234567890abcdef"},
                },
            )
        payload = json.loads(request.content)
        assert payload == {
            "attributes": {
                "serial": "ml-android-1234567890abcdef",
                "mission-leben.de/status": "disabled",
                "mission-leben.de/disabled-at": "2026-09-20T13:45:00Z",
                "mission-leben.de/disabled-reason": "replaced",
            },
            "expiring": False,
            "expires": None,
        }
        return httpx.Response(200, json={"device_uuid": device_uuid, **payload})

    client = AuthentikClient(settings, httpx.MockTransport(handler))
    try:
        result = await client.disable_device(device_uuid, disabled_at, "replaced")
        assert result["attributes"]["mission-leben.de/status"] == "disabled"
        assert calls == [
            ("GET", f"/api/v3/endpoints/devices/{device_uuid}/"),
            ("PATCH", f"/api/v3/endpoints/devices/{device_uuid}/"),
        ]
    finally:
        await client.close()
