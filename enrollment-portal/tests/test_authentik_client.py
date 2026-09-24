from __future__ import annotations

import json
from datetime import UTC, datetime

import httpx
import pytest

from mission_leben_device_enrollment.authentik import (
    AuthentikClient,
    AuthentikError,
    is_personal_employee,
    is_real_facility,
    is_shared_handset_account,
)


@pytest.mark.parametrize(
    ("overrides", "expected"),
    [
        ({}, True),
        ({"is_active": False}, False),
        ({"type": "service_account"}, False),
        ({"attributes": {"iam_account_kind": "shared", "iam_directory_class": "person"}}, False),
        ({"attributes": {"iam_account_kind": "person", "iam_directory_class": "system"}}, False),
        ({"attributes": {"iam_account_kind": "person"}}, False),
        ({"attributes": {}}, False),
    ],
)
def test_personal_employee_requires_canonical_physical_person_attributes(overrides, expected):
    user = {
        "is_active": True,
        "type": "internal",
        "attributes": {
            "iam_account_kind": "person",
            "iam_directory_class": "person",
        },
        **overrides,
    }

    assert is_personal_employee(user) is expected


@pytest.mark.parametrize(
    ("overrides", "expected"),
    [
        ({}, True),
        ({"is_active": False}, False),
        ({"type": "service_account"}, False),
        ({"attributes": {"iam_account_kind": "person", "iam_directory_class": "person"}}, False),
        ({"attributes": {"iam_account_kind": "shared", "iam_directory_class": "system"}}, False),
        ({"attributes": {"iam_account_kind": "shared", "iam_directory_class": "mailbox", "iam_interactive_login_allowed": False, "iam_noninteractive_account": False}}, False),
        ({"attributes": {"iam_account_kind": "shared", "iam_directory_class": "mailbox", "iam_interactive_login_allowed": True, "iam_noninteractive_account": True}}, False),
        ({"attributes": {"iam_account_kind": "shared", "iam_directory_class": "mailbox"}}, False),
    ],
)
def test_shared_handset_preflight_rejects_noninteractive_or_ambiguous_accounts(overrides, expected):
    user = {
        "is_active": True,
        "type": "internal",
        "attributes": {
            "iam_account_kind": "shared",
            "iam_directory_class": "mailbox",
            "iam_interactive_login_allowed": True,
            "iam_noninteractive_account": False,
        },
        **overrides,
    }
    assert is_shared_handset_account(user) is expected


@pytest.mark.asyncio
async def test_shared_handset_search_and_target_lookup_filter_canonically(settings):
    eligible = {
        "pk": 7,
        "uuid": "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
        "username": "team.example",
        "is_active": True,
        "type": "internal",
        "attributes": {
            "iam_account_kind": "shared",
            "iam_directory_class": "mailbox",
            "iam_interactive_login_allowed": True,
            "iam_noninteractive_account": False,
        },
    }
    rejected = {**eligible, "pk": 8, "attributes": {**eligible["attributes"], "iam_noninteractive_account": True}}

    def handler(request: httpx.Request) -> httpx.Response:
        if request.url.path.endswith("/core/users/7/"):
            return httpx.Response(200, json=eligible)
        if request.url.path.endswith("/core/users/8/"):
            return httpx.Response(200, json=rejected)
        assert request.url.path.endswith("/core/users/")
        assert request.url.params.get("search") == "team"
        return httpx.Response(200, json={"results": [rejected, eligible]})

    client = AuthentikClient(settings, httpx.MockTransport(handler))
    try:
        assert await client.shared_handset_accounts("t") == []
        assert [user["pk"] for user in await client.shared_handset_accounts("team")] == [7]
        assert (await client.shared_handset_account(7))["username"] == "team.example"
        with pytest.raises(AuthentikError) as error:
            await client.shared_handset_account(8)
        assert error.value.status == 400
    finally:
        await client.close()


def facility(name="ORG_ML_H042", **attribute_overrides):
    return {
        "name": name,
        "attributes": {
            "iam_group_type": "organization_unit",
            "iam_managed": True,
            "iam_plan_status": "AKTIV",
            "iam_org_level": "Einrichtung",
            **attribute_overrides,
        },
    }


@pytest.mark.parametrize(
    ("group", "expected"),
    [
        (facility(), True),
        (facility("ORG_ML_H050_01", iam_org_level="Einrichtung/Verbund"), True),
        (facility("ORG_ML_H001", iam_org_level="Geschäftseinheit/Standort"), True),
        (facility("ORG_ML_H031_01", iam_org_level="Einrichtung/Teilbetrieb"), False),
        (facility("ORG_ML_H044_TFH"), False),
        (facility("ORG_ML_ZD_IT"), False),
        (facility(iam_group_type="organization_house"), False),
        (facility(iam_managed=False), False),
        (facility(iam_plan_status="PLANUNG"), False),
        (facility(iam_org_level="Teilbereich"), False),
        (facility("ORG_ML_H002", iam_org_level="Geschäftseinheit/Standort"), False),
    ],
)
def test_real_facility_matches_canonical_interface(group, expected):
    assert is_real_facility(group, "ORG_ML_H") is expected


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
async def test_access_group_reads_exact_authentik_record(settings):
    group_uuid = "dddddddd-bbbb-cccc-dddd-eeeeeeeeeeee"

    def handler(request: httpx.Request) -> httpx.Response:
        assert request.url.path == f"/api/v3/endpoints/device_access_groups/{group_uuid}/"
        return httpx.Response(200, json={"pbm_uuid": group_uuid, "attributes": {}})

    client = AuthentikClient(settings, httpx.MockTransport(handler))
    try:
        result = await client.access_group(group_uuid)
        assert result["pbm_uuid"] == group_uuid
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


@pytest.mark.asyncio
async def test_device_assignment_is_searchable_and_preserves_existing_attributes(settings):
    device_uuid = "eeeeeeee-bbbb-cccc-dddd-eeeeeeeeeeee"
    calls = []

    def handler(request: httpx.Request) -> httpx.Response:
        calls.append((request.method, request.url.path))
        if request.method == "GET":
            return httpx.Response(
                200,
                json={
                    "device_uuid": device_uuid,
                    "name": "TCL T807D (12345678)",
                    "attributes": {"serial": "ml-android-1234567890abcdef"},
                },
            )
        payload = json.loads(request.content)
        assert payload == {
            "name": "TCL T807D (12345678) · m.beispiel",
            "attributes": {
                "serial": "ml-android-1234567890abcdef",
                "mission-leben.de/purpose": "android-portal",
                "mission-leben.de/mode": "personal",
                "mission-leben.de/assigned-kind": "user",
                "mission-leben.de/assigned-to": "m.beispiel",
            },
        }
        return httpx.Response(200, json={"device_uuid": device_uuid, **payload})

    client = AuthentikClient(settings, httpx.MockTransport(handler))
    try:
        result = await client.update_device_assignment(
            device_uuid,
            "TCL T807D (12345678) · m.beispiel",
            "personal",
            "m.beispiel",
        )
        assert result["name"].endswith("· m.beispiel")
        assert calls == [
            ("GET", f"/api/v3/endpoints/devices/{device_uuid}/"),
            ("PATCH", f"/api/v3/endpoints/devices/{device_uuid}/"),
        ]
    finally:
        await client.close()
