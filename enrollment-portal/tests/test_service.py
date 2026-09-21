from __future__ import annotations

from dataclasses import replace
from datetime import UTC, datetime, timedelta
from urllib.parse import urlsplit

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
        self.disabled_devices = []
        self.updated_device_assignments = []
        self.login_approval_devices = []
        self._bindings = []
        self.existing_group = None
        self.device_uuid = "eeeeeeee-bbbb-cccc-dddd-eeeeeeeeeeee"
        self.device_records = []
        self.fail_enrollment = False
        self.fail_disable_for = None
        self.user = {
            "pk": 42,
            "uuid": "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
            "username": "m.beispiel",
            "uid": "authentik-stable-subject",
            "name": "Maria Beispiel",
            "is_active": True,
            "type": "internal",
            "groups_obj": [
                {
                    "pk": "bbbbbbbb-bbbb-cccc-dddd-eeeeeeeeeeee",
                    "name": "ORG_ML_H042",
                    "attributes": {"iam_group_type": "organization_house"},
                }
            ],
        }
        self.token_record = {
            "token_uuid": "cccccccc-bbbb-cccc-dddd-eeeeeeeeeeee",
            "connector": settings.agent_connector_uuid,
            "device_group": "dddddddd-bbbb-cccc-dddd-eeeeeeeeeeee",
            "device_group_obj": {
                "pbm_uuid": "dddddddd-bbbb-cccc-dddd-eeeeeeeeeeee",
                "name": "Mission Leben Android - Personal",
                "attributes": {
                    "mission-leben.de/purpose": "android-portal",
                    "mission-leben.de/mode": "personal",
                    "mission-leben.de/user-uuid": "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
                    "mission-leben.de/username": "m.beispiel",
                },
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

    async def user_record(self, user_pk):
        assert user_pk == self.user["pk"]
        return self.user

    async def employee_by_username(self, username):
        assert username == self.user["username"]
        return self.user

    async def access_group_by_name(self, _):
        return self.existing_group

    async def access_group(self, group_uuid):
        assert group_uuid == self.token_record["device_group"]
        return self.token_record["device_group_obj"]

    async def create_access_group(self, name, attributes):
        group = {
            "pbm_uuid": "dddddddd-bbbb-cccc-dddd-eeeeeeeeeeee",
            "name": name,
            "attributes": attributes,
        }
        self.created_groups.append(group)
        return group

    async def update_access_group(self, group_uuid, name, attributes):
        self.existing_group = {
            "pbm_uuid": group_uuid,
            "name": name,
            "attributes": attributes,
        }
        return self.existing_group

    async def bindings(self, _):
        return self._bindings

    async def create_user_binding(self, target_uuid, user_pk):
        self.created_bindings.append(("user", target_uuid, user_pk))

    async def create_group_binding(self, target_uuid, group_uuid):
        self.created_bindings.append(("group", target_uuid, group_uuid))

    async def devices(self, access_group_uuid=None):
        if access_group_uuid is None:
            return list(self.device_records)
        return [
            device
            for device in self.device_records
            if device.get("access_group") == access_group_uuid
        ]

    async def device(self, device_uuid):
        return next(
            device for device in self.device_records if device["device_uuid"] == device_uuid
        )

    async def disable_device(self, device_uuid, disabled_at, reason):
        if device_uuid == self.fail_disable_for:
            raise AuthentikError(500, "forced disable failure")
        device = await self.device(device_uuid)
        device["expiring"] = False
        device["expires"] = None
        device["attributes"] = {
            **device.get("attributes", {}),
            "mission-leben.de/status": "disabled",
            "mission-leben.de/disabled-at": disabled_at.isoformat(),
            "mission-leben.de/disabled-reason": reason,
        }
        self.disabled_devices.append(device_uuid)
        return device

    async def update_device_assignment(self, device_uuid, display_name, mode, assigned_to):
        device = await self.device(device_uuid)
        device["name"] = display_name
        device["attributes"] = {
            **device.get("attributes", {}),
            "mission-leben.de/purpose": "android-portal",
            "mission-leben.de/mode": mode,
            "mission-leben.de/assigned-kind": (
                "user" if mode == "personal" else "organization"
            ),
            "mission-leben.de/assigned-to": assigned_to,
        }
        self.updated_device_assignments.append(
            (device_uuid, display_name, mode, assigned_to)
        )
        return device

    async def ensure_login_approval_device(self, username, subject):
        self.login_approval_devices.append((username, subject))

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
        if self.fail_enrollment:
            raise AuthentikError(500, "forced enrollment failure")
        self.enrolled.append((token, payload))
        existing = next(
            (
                device
                for device in self.device_records
                if device.get("attributes", {}).get("serial") == payload["device_serial"]
            ),
            None,
        )
        if existing is None:
            self.device_records.append(
                {
                    "device_uuid": self.device_uuid,
                    "name": payload["device_name"],
                    "access_group": self.token_record["device_group"],
                    "access_group_obj": self.token_record["device_group_obj"],
                    "expiring": False,
                    "expires": None,
                    "facts": None,
                    "attributes": {"serial": payload["device_serial"]},
                }
            )
        return {"token": "agent-device-token"}

    async def agent_device_id(self, _):
        return self.device_uuid

    async def expire_enrollment_token(self, _):
        raise AssertionError("delete should work")


def actor(role=Role.EL, organizations=frozenset({"ORG_ML_H042"})):
    roles = frozenset() if role is None else frozenset({role})
    return Actor("actor-id", "leitung.test", "Leitung Test", roles, organizations)


@pytest.mark.asyncio
async def test_personal_enrollment_is_bound_before_qr_is_issued(settings):
    authentik = FakeAuthentik(settings)
    service = EnrollmentService(settings, authentik)

    issued = await service.issue_personal(actor(), 42)

    assert authentik.created_groups[0]["attributes"]["mission-leben.de/user-uuid"] == authentik.user["uuid"]
    assert authentik.created_bindings == [
        ("user", "dddddddd-bbbb-cccc-dddd-eeeeeeeeeeee", 42)
    ]
    assert authentik.login_approval_devices == [
        (authentik.user["username"], authentik.user["uid"])
    ]
    assert "token_id=" in issued.deep_link()
    assert "mode=personal" in issued.deep_link()
    assert issued.install_link(settings.public_origin).startswith(
        "https://geraete.example.org/install#token="
    )
    split_install_link = urlsplit(issued.install_link(settings.public_origin))
    assert split_install_link.query == ""
    assert "token=abcdefghijklmnopqrstuvwxyz0123456789_-" in split_install_link.fragment
    assert authentik.audit_events[0][0] == "model_created"


@pytest.mark.asyncio
async def test_personal_enrollment_fails_outside_el_scope(settings):
    authentik = FakeAuthentik(settings)
    service = EnrollmentService(settings, authentik)

    with pytest.raises(AuthentikError) as error:
        await service.issue_personal(actor(organizations=frozenset({"ORG_ML_H043"})), 42)
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
        frozenset(),
        frozenset(),
    )

    issued = await service.issue_self_personal(self_actor)

    assert issued.mode == "personal"
    assert authentik.created_bindings == [
        ("user", "dddddddd-bbbb-cccc-dddd-eeeeeeeeeeee", authentik.user["pk"])
    ]
    assert authentik.login_approval_devices == [
        (authentik.user["username"], authentik.user["uid"])
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
    assert authentik.enrolled[0][1]["device_name"] == "TCL T807D (12345678) · m.beispiel"
    assert authentik.updated_device_assignments == [
        (
            authentik.device_uuid,
            "TCL T807D (12345678) · m.beispiel",
            "personal",
            "m.beispiel",
        )
    ]
    assert authentik.device_records[-1]["attributes"]["mission-leben.de/assigned-to"] == "m.beispiel"
    assert authentik.audit_events[-1][0] == "model_updated"


@pytest.mark.asyncio
async def test_shared_redeem_is_labeled_with_its_organization(settings):
    authentik = FakeAuthentik(settings)
    authentik.token_record["device_group_obj"]["attributes"] = {
        "mission-leben.de/purpose": "android-portal",
        "mission-leben.de/mode": "shared",
        "mission-leben.de/facility-group": "ORG_ML_H042",
    }
    service = EnrollmentService(settings, authentik)

    result = await service.redeem(
        authentik.token_record["token_uuid"],
        "abcdefghijklmnopqrstuvwxyz0123456789_-",
        "shared",
        "ml-android-1234567890abcdef",
        "Samsung Tablet (12345678)",
    )

    assert result == {"token": "agent-device-token"}
    assert authentik.enrolled[0][1]["device_name"] == (
        "Samsung Tablet (12345678) · ORG_ML_H042"
    )
    assert authentik.updated_device_assignments == [
        (
            authentik.device_uuid,
            "Samsung Tablet (12345678) · ORG_ML_H042",
            "shared",
            "ORG_ML_H042",
        )
    ]
    assert authentik.device_records[-1]["attributes"]["mission-leben.de/assigned-kind"] == (
        "organization"
    )


@pytest.mark.asyncio
async def test_personal_redeem_disables_previous_device_only_after_new_enrollment(settings):
    authentik = FakeAuthentik(settings)
    old_device_uuid = "ffffffff-bbbb-cccc-dddd-eeeeeeeeeeee"
    authentik.device_records.append(
        {
            "device_uuid": old_device_uuid,
            "name": "Altes Handy",
            "access_group": authentik.token_record["device_group"],
            "access_group_obj": authentik.token_record["device_group_obj"],
            "expiring": False,
            "expires": None,
            "facts": {"created": "2026-09-19T08:15:00Z"},
            "attributes": {"serial": "ml-android-fedcba0987654321"},
        }
    )
    service = EnrollmentService(settings, authentik)

    await service.redeem(
        authentik.token_record["token_uuid"],
        "abcdefghijklmnopqrstuvwxyz0123456789_-",
        "personal",
        "ml-android-1234567890abcdef",
        "Neues Handy",
    )

    assert authentik.enrolled
    assert authentik.disabled_devices == [old_device_uuid]
    assert authentik.device_records[0]["expiring"] is False
    assert authentik.device_records[0]["expires"] is None
    assert authentik.device_records[0]["attributes"]["mission-leben.de/status"] == "disabled"
    assert authentik.device_records[0]["attributes"]["mission-leben.de/disabled-reason"] == "replaced"
    assert authentik.device_records[-1]["device_uuid"] == authentik.device_uuid
    assert authentik.device_records[-1]["expiring"] is False
    assert authentik.audit_events[-1][2]["replaced_device_uuids"] == [old_device_uuid]


@pytest.mark.asyncio
async def test_reenrolling_same_device_does_not_disable_it(settings):
    authentik = FakeAuthentik(settings)
    authentik.device_records.append(
        {
            "device_uuid": authentik.device_uuid,
            "name": "Vorhandenes Handy",
            "access_group": authentik.token_record["device_group"],
            "access_group_obj": authentik.token_record["device_group_obj"],
            "expiring": False,
            "expires": None,
            "facts": None,
            "attributes": {"serial": "ml-android-1234567890abcdef"},
        }
    )
    service = EnrollmentService(settings, authentik)

    await service.redeem(
        authentik.token_record["token_uuid"],
        "abcdefghijklmnopqrstuvwxyz0123456789_-",
        "personal",
        "ml-android-1234567890abcdef",
        "Vorhandenes Handy",
    )

    assert authentik.disabled_devices == []


@pytest.mark.asyncio
async def test_failed_enrollment_keeps_previous_device_active(settings):
    authentik = FakeAuthentik(settings)
    old_device_uuid = "ffffffff-bbbb-cccc-dddd-eeeeeeeeeeee"
    authentik.device_records.append(
        {
            "device_uuid": old_device_uuid,
            "name": "Altes Handy",
            "access_group": authentik.token_record["device_group"],
            "access_group_obj": authentik.token_record["device_group_obj"],
            "expiring": False,
            "expires": None,
            "facts": None,
            "attributes": {"serial": "ml-android-fedcba0987654321"},
        }
    )
    authentik.fail_enrollment = True
    service = EnrollmentService(settings, authentik)

    with pytest.raises(AuthentikError):
        await service.redeem(
            authentik.token_record["token_uuid"],
            "abcdefghijklmnopqrstuvwxyz0123456789_-",
            "personal",
            "ml-android-1234567890abcdef",
            "Neues Handy",
        )

    assert authentik.disabled_devices == []
    assert authentik.device_records[0]["expiring"] is False


@pytest.mark.asyncio
async def test_failed_old_device_disable_locks_new_device(settings):
    authentik = FakeAuthentik(settings)
    old_device_uuid = "ffffffff-bbbb-cccc-dddd-eeeeeeeeeeee"
    authentik.device_records.append(
        {
            "device_uuid": old_device_uuid,
            "name": "Altes Handy",
            "access_group": authentik.token_record["device_group"],
            "access_group_obj": authentik.token_record["device_group_obj"],
            "expiring": False,
            "expires": None,
            "facts": None,
            "attributes": {"serial": "ml-android-fedcba0987654321"},
        }
    )
    authentik.fail_disable_for = old_device_uuid
    service = EnrollmentService(settings, authentik)

    with pytest.raises(AuthentikError) as error:
        await service.redeem(
            authentik.token_record["token_uuid"],
            "abcdefghijklmnopqrstuvwxyz0123456789_-",
            "personal",
            "ml-android-1234567890abcdef",
            "Neues Handy",
        )

    assert error.value.status == 502
    assert authentik.disabled_devices == [authentik.device_uuid]
    assert authentik.device_records[-1]["attributes"]["mission-leben.de/disabled-reason"] == "replacement-failed"
    assert authentik.device_records[0]["expiring"] is False


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
        "x-authentik-groups": "BR_EINRICHTUNGSLEITUNG|ORG_ML_H042",
    }
    csrf_token = CsrfProtector(settings.csrf_secret, settings.public_origin).issue(
        actor(), "issue-personal"
    )
    with TestClient(app) as client:
        home = client.get("/", headers=headers)
        assert home.status_code == 200
        assert "Mitarbeiter-Handy" in home.text
        assert "App noch nicht installiert?" in home.text
        assert settings.apk_download_url in home.text
        assert "<svg" in home.text
        assert "default-src 'none'" in home.headers["content-security-policy"]
        assert home.headers["referrer-policy"] == "same-origin"

        response = client.post(
            "/personal/enrollments",
            headers={**headers, "origin": settings.public_origin},
            data={"employee_pk": "42", "csrf_token": csrf_token},
        )
        assert response.status_code == 200
        assert "Ein QR-Code für Installation und Einrichtung" in response.text
        assert "<svg" in response.text
        assert "abcdefghijklmnopqrstuvwxyz0123456789_-" not in response.text

        installer = client.get("/install#fragment-is-not-sent")
        assert installer.status_code == 200
        assert "App herunterladen" in installer.text
        assert settings.apk_download_url in installer.text
        assert "script-src 'self'" in installer.headers["content-security-policy"]
        assert "script-src" not in home.headers["content-security-policy"]

        asset_links_unconfigured = client.get("/.well-known/assetlinks.json")
        assert asset_links_unconfigured.status_code == 503

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


def test_device_status_reads_live_authentik_device_state(settings):
    authentik = FakeAuthentik(settings)
    authentik.token_record["device_group_obj"]["attributes"] = {
        "mission-leben.de/purpose": "android-portal",
        "mission-leben.de/mode": "personal",
    }
    authentik._bindings = [
        {
            "enabled": True,
            "negate": False,
            "policy": None,
            "user": authentik.user["pk"],
            "group": None,
        }
    ]
    authentik.device_records.append(
        {
            "device_uuid": authentik.device_uuid,
            "name": "Testgerät",
            "access_group": "dddddddd-bbbb-cccc-dddd-eeeeeeeeeeee",
            "expiring": False,
            "expires": None,
            "attributes": {"mission-leben.de/status": "active"},
        }
    )
    app = create_app(settings, authentik)

    with TestClient(app) as client:
        active = client.get(
            "/api/v1/devices/status",
            headers={"authorization": "Bearer+Agent abcdefghijklmnopqrstuvwxyz0123456789_-"},
        )
        assert active.status_code == 200
        assert active.json() == {"device_id": authentik.device_uuid, "trusted": True}

        authentik.device_records[0]["attributes"]["mission-leben.de/status"] = "disabled"
        disabled = client.get(
            "/api/v1/devices/status",
            headers={"authorization": "Bearer+Agent abcdefghijklmnopqrstuvwxyz0123456789_-"},
        )
        assert disabled.status_code == 403
        assert disabled.json() == {"error": "Das Gerät ist deaktiviert oder abgelaufen."}


def test_device_status_rejects_inactive_bound_user(settings):
    authentik = FakeAuthentik(settings)
    authentik.token_record["device_group_obj"]["attributes"] = {
        "mission-leben.de/purpose": "android-portal",
        "mission-leben.de/mode": "personal",
    }
    authentik._bindings = [
        {
            "enabled": True,
            "negate": False,
            "policy": None,
            "user": authentik.user["pk"],
            "group": None,
        }
    ]
    authentik.user["is_active"] = False
    authentik.device_records.append(
        {
            "device_uuid": authentik.device_uuid,
            "name": "Testgerät",
            "access_group": authentik.token_record["device_group"],
            "expiring": False,
            "expires": None,
            "attributes": {"mission-leben.de/status": "active"},
        }
    )
    app = create_app(settings, authentik)

    with TestClient(app) as client:
        response = client.get(
            "/api/v1/devices/status",
            headers={"authorization": "Bearer+Agent abcdefghijklmnopqrstuvwxyz0123456789_-"},
        )

    assert response.status_code == 403
    assert response.json() == {"error": "Der zugeordnete Mitarbeiter ist deaktiviert."}


def test_device_status_rejects_ambiguous_personal_binding(settings):
    authentik = FakeAuthentik(settings)
    authentik.token_record["device_group_obj"]["attributes"] = {
        "mission-leben.de/purpose": "android-portal",
        "mission-leben.de/mode": "personal",
    }
    authentik._bindings = []
    authentik.device_records.append(
        {
            "device_uuid": authentik.device_uuid,
            "name": "Testgerät",
            "access_group": authentik.token_record["device_group"],
            "expiring": False,
            "expires": None,
            "attributes": {"mission-leben.de/status": "active"},
        }
    )
    app = create_app(settings, authentik)

    with TestClient(app) as client:
        response = client.get(
            "/api/v1/devices/status",
            headers={"authorization": "Bearer+Agent abcdefghijklmnopqrstuvwxyz0123456789_-"},
        )

    assert response.status_code == 403
    assert response.json() == {"error": "Die persönliche Gerätebindung ist nicht eindeutig."}


def test_device_status_rejects_missing_or_malformed_agent_token(settings):
    app = create_app(settings, FakeAuthentik(settings))

    with TestClient(app) as client:
        missing = client.get("/api/v1/devices/status")
        malformed = client.get(
            "/api/v1/devices/status",
            headers={"authorization": "Bearer+Agent contains spaces and is long enough"},
        )

    assert missing.status_code == 401
    assert malformed.status_code == 401


def test_asset_links_publishes_only_configured_production_certificate(settings):
    fingerprint = ":".join(["AB"] * 32)
    configured = replace(
        settings,
        android_cert_sha256_fingerprints=(fingerprint,),
    )
    app = create_app(configured, FakeAuthentik(configured))

    with TestClient(app) as client:
        response = client.get("/.well-known/assetlinks.json")

    assert response.status_code == 200
    assert response.json() == [
        {
            "relation": ["delegate_permission/common.handle_all_urls"],
            "target": {
                "namespace": "android_app",
                "package_name": "de.missionleben.portal",
                "sha256_cert_fingerprints": [fingerprint],
            },
        }
    ]


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
        frozenset(),
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
        assert response.status_code == 200
        assert "Gerätecode erstellt" in response.text
        assert "Mission Leben Zentral öffnen" in response.text
        assert "de.missionleben.portal://enroll?" in response.text
        assert "mode=personal" in response.text
        assert settings.public_origin + "/install#" in response.text
        assert "Der Code gilt bis" in response.text
        assert "location" not in response.headers
        assert authentik.created_bindings == [
            ("user", "dddddddd-bbbb-cccc-dddd-eeeeeeeeeeee", authentik.user["pk"])
        ]
