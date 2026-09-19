from __future__ import annotations

from datetime import UTC, datetime, timedelta

import pytest
from fastapi.testclient import TestClient

from mission_leben_device_enrollment.app import create_app
from mission_leben_device_enrollment.auth import Actor, CsrfProtector, Role
from mission_leben_device_enrollment.authentik import AuthentikError
from mission_leben_device_enrollment.service import EnrollmentService


class FakeAuthentik:
    def __init__(self, settings):
        self.settings = settings
        self.created_groups = []
        self.created_bindings = []
        self.audit_events = []
        self.deleted_tokens = []
        self.enrolled = []
        self._bindings = []
        self.user = {
            "pk": 42,
            "uuid": "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
            "username": "m.beispiel",
            "name": "Maria Beispiel",
            "is_active": True,
            "type": "internal",
            "groups_obj": [
                {
                    "pk": "bbbbbbbb-bbbb-cccc-dddd-eeeeeeeeeeee",
                    "name": "ORG_HAUS_1",
                    "attributes": {"iam_group_type": "organization_house"},
                }
            ],
        }
        self.token_record = {
            "token_uuid": "cccccccc-bbbb-cccc-dddd-eeeeeeeeeeee",
            "connector": settings.agent_connector_uuid,
            "device_group": "dddddddd-bbbb-cccc-dddd-eeeeeeeeeeee",
            "device_group_obj": {
                "name": "Mission Leben Android - Personal",
                "attributes": {"mission-leben.de/mode": "personal"},
            },
            "name": "test",
            "expiring": True,
            "expires": (datetime.now(UTC) + timedelta(minutes=5)).isoformat(),
        }

    async def close(self):
        return None

    def _is_organization(self, group):
        return group.get("name", "").startswith("ORG_") and group.get("attributes", {}).get(
            "iam_group_type"
        ) in {"organization_house", "organization_unit"}

    async def employee(self, user_pk):
        assert user_pk == self.user["pk"]
        return self.user

    async def employee_by_username(self, username):
        assert username == self.user["username"]
        return self.user

    async def access_group_by_name(self, _):
        return None

    async def create_access_group(self, name, attributes):
        group = {
            "pbm_uuid": "dddddddd-bbbb-cccc-dddd-eeeeeeeeeeee",
            "name": name,
            "attributes": attributes,
        }
        self.created_groups.append(group)
        return group

    async def update_access_group(self, *_):
        raise AssertionError("unexpected update")

    async def bindings(self, _):
        return self._bindings

    async def create_user_binding(self, target_uuid, user_pk):
        self.created_bindings.append(("user", target_uuid, user_pk))

    async def create_group_binding(self, target_uuid, group_uuid):
        self.created_bindings.append(("group", target_uuid, group_uuid))

    async def create_enrollment_token(self, name, access_group_uuid, expires):
        return {
            "token_uuid": self.token_record["token_uuid"],
            "name": name,
            "device_group": access_group_uuid,
            "expires": expires.isoformat(),
        }

    async def enrollment_token_key(self, _):
        return "abcdefghijklmnopqrstuvwxyz0123456789_-"

    async def audit(self, action, actor, context):
        self.audit_events.append((action, actor, context))

    async def delete_enrollment_token(self, token_uuid):
        self.deleted_tokens.append(token_uuid)

    async def enrollment_token(self, token_uuid):
        if token_uuid in self.deleted_tokens:
            raise AuthentikError(404, "not found")
        return self.token_record

    async def token_matches(self, _, presented):
        return presented == "abcdefghijklmnopqrstuvwxyz0123456789_-"

    async def enroll_agent(self, token, payload):
        self.enrolled.append((token, payload))
        return {"token": "agent-device-token"}

    async def expire_enrollment_token(self, _):
        raise AssertionError("delete should work")


def actor(role=Role.EL, organizations=frozenset({"ORG_HAUS_1"})):
    return Actor("actor-id", "leitung.test", "Leitung Test", role, organizations)


@pytest.mark.asyncio
async def test_personal_enrollment_is_bound_before_qr_is_issued(settings):
    authentik = FakeAuthentik(settings)
    service = EnrollmentService(settings, authentik)

    issued = await service.issue_personal(actor(), 42)

    assert authentik.created_groups[0]["attributes"]["mission-leben.de/user-uuid"] == authentik.user["uuid"]
    assert authentik.created_bindings == [
        ("user", "dddddddd-bbbb-cccc-dddd-eeeeeeeeeeee", 42)
    ]
    assert "token_id=" in issued.qr_payload()
    assert "mode=personal" in issued.qr_payload()
    assert authentik.audit_events[0][0] == "model_created"


@pytest.mark.asyncio
async def test_personal_enrollment_fails_outside_el_scope(settings):
    authentik = FakeAuthentik(settings)
    service = EnrollmentService(settings, authentik)

    with pytest.raises(AuthentikError) as error:
        await service.issue_personal(actor(organizations=frozenset({"ORG_HAUS_2"})), 42)
    assert error.value.status == 403
    assert not authentik.created_groups


@pytest.mark.asyncio
async def test_totp_self_enrollment_binds_only_authenticated_employee(settings):
    authentik = FakeAuthentik(settings)
    service = EnrollmentService(settings, authentik)
    self_actor = Actor(
        "self-id",
        authentik.user["username"],
        authentik.user["name"],
        None,
        frozenset(),
    )

    issued = await service.issue_self_personal(self_actor)

    assert issued.mode == "personal"
    assert authentik.created_bindings == [
        ("user", "dddddddd-bbbb-cccc-dddd-eeeeeeeeeeee", authentik.user["pk"])
    ]
    assert authentik.audit_events[0][1]["role"] == "self_totp"


@pytest.mark.asyncio
async def test_existing_unexpected_binding_fails_closed(settings):
    authentik = FakeAuthentik(settings)
    authentik._bindings = [
        {"enabled": True, "user": 99, "group": None, "policy": None, "negate": False}
    ]
    service = EnrollmentService(settings, authentik)

    with pytest.raises(AuthentikError) as error:
        await service.issue_personal(actor(), 42)
    assert error.value.status == 409


@pytest.mark.asyncio
async def test_redeem_enrolls_in_authentik_and_deletes_token(settings):
    authentik = FakeAuthentik(settings)
    service = EnrollmentService(settings, authentik)
    result = await service.redeem(
        authentik.token_record["token_uuid"],
        "abcdefghijklmnopqrstuvwxyz0123456789_-",
        "personal",
        "ml-android-1234567890abcdef",
        "TCL T807D (12345678)",
    )

    assert result == {"token": "agent-device-token"}
    assert authentik.deleted_tokens == [authentik.token_record["token_uuid"]]
    assert authentik.enrolled[0][1]["device_serial"] == "ml-android-1234567890abcdef"
    assert authentik.audit_events[-1][0] == "model_updated"


@pytest.mark.asyncio
async def test_redeem_rejects_wrong_mode_or_token(settings):
    authentik = FakeAuthentik(settings)
    service = EnrollmentService(settings, authentik)
    with pytest.raises(AuthentikError) as wrong_mode:
        await service.redeem(
            authentik.token_record["token_uuid"],
            "abcdefghijklmnopqrstuvwxyz0123456789_-",
            "shared",
            "ml-android-1234567890abcdef",
            "Tablet",
        )
    assert wrong_mode.value.status == 403

    with pytest.raises(AuthentikError) as wrong_token:
        await service.redeem(
            authentik.token_record["token_uuid"],
            "wrong-but-long-enough-token-value",
            "personal",
            "ml-android-1234567890abcdef",
            "Tablet",
        )
    assert wrong_token.value.status == 401


def test_simple_management_page_renders_qr_without_exposing_token_as_text(settings):
    authentik = FakeAuthentik(settings)
    app = create_app(settings, authentik)
    headers = {
        "x-authentik-meta-app": settings.proxy_app_slug,
        "x-authentik-uid": "actor-id",
        "x-authentik-username": "leitung.test",
        "x-authentik-name": "Leitung Test",
        "x-authentik-groups": "ML_DEVICE_INIT_EL|ORG_HAUS_1",
    }
    csrf_token = CsrfProtector(settings.csrf_secret, settings.public_origin).issue(
        actor(), "issue-personal"
    )
    with TestClient(app) as client:
        home = client.get("/", headers=headers)
        assert home.status_code == 200
        assert "Mitarbeiter-Handy" in home.text
        assert "default-src 'none'" in home.headers["content-security-policy"]

        response = client.post(
            "/personal/enrollments",
            headers={**headers, "origin": settings.public_origin},
            data={"employee_pk": "42", "csrf_token": csrf_token},
        )
        assert response.status_code == 200
        assert "Jetzt das neue Gerät scannen lassen" in response.text
        assert "<svg" in response.text
        assert "abcdefghijklmnopqrstuvwxyz0123456789_-" not in response.text

        invalid_redeem = client.post(
            "/api/v1/enrollments/cccccccc-bbbb-cccc-dddd-eeeeeeeeeeee/redeem",
            headers={"authorization": "Bearer zu-kurz"},
            json={
                "mode": "personal",
                "device_serial": "ml-android-1234567890abcdef",
                "device_name": "Testgerät",
            },
        )
        assert invalid_redeem.status_code == 401
        assert invalid_redeem.json() == {"error": "Registrierungscode ist ungültig."}


def test_normal_employee_can_only_create_own_enrollment(settings):
    authentik = FakeAuthentik(settings)
    app = create_app(settings, authentik)
    headers = {
        "x-authentik-meta-app": settings.proxy_app_slug,
        "x-authentik-uid": "self-id",
        "x-authentik-username": authentik.user["username"],
        "x-authentik-name": authentik.user["name"],
        "x-authentik-groups": "Mitarbeitende",
    }
    self_actor = Actor(
        "self-id",
        authentik.user["username"],
        authentik.user["name"],
        None,
        frozenset(),
    )
    csrf_token = CsrfProtector(settings.csrf_secret, settings.public_origin).issue(
        self_actor, "issue-self-personal"
    )

    with TestClient(app) as client:
        page = client.get("/self", headers=headers)
        assert page.status_code == 200
        assert "Dieses Gerät registrieren" in page.text

        management = client.get("/", headers=headers)
        assert management.status_code == 403

        response = client.post(
            "/self/enrollments",
            headers={**headers, "origin": settings.public_origin},
            data={"csrf_token": csrf_token},
            follow_redirects=False,
        )
        assert response.status_code == 303
        assert response.headers["location"].startswith("de.missionleben.portal://enroll?")
        assert "mode=personal" in response.headers["location"]
        assert authentik.created_bindings == [
            ("user", "dddddddd-bbbb-cccc-dddd-eeeeeeeeeeee", authentik.user["pk"])
        ]
