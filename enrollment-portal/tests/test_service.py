from __future__ import annotations

from dataclasses import replace
from datetime import UTC, datetime, timedelta
from urllib.parse import urlsplit

import pytest
from fastapi.testclient import TestClient

from mission_leben_device_enrollment.app import create_app
from mission_leben_device_enrollment.auth import Actor, CsrfProtector, Role
from mission_leben_device_enrollment.authentik import (
    AuthentikError,
    is_real_facility,
    is_shared_handset_account,
)
from mission_leben_device_enrollment.service import (
    HANDSET_PROFILE_ATTRIBUTE,
    PENDING_ENROLLMENT_ATTRIBUTE,
    EnrollmentService,
)


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
            "email": "m.beispiel@mission-leben.de",
            "uid": "authentik-stable-subject",
            "name": "Maria Beispiel",
            "is_active": True,
            "type": "internal",
            "attributes": {
                "iam_account_kind": "person",
                "iam_directory_class": "person",
            },
            "groups_obj": [
                {
                    "pk": "bbbbbbbb-bbbb-cccc-dddd-eeeeeeeeeeee",
                    "name": "ORG_ML_H042",
                    "attributes": {
                        "iam_group_type": "organization_unit",
                        "iam_managed": True,
                        "iam_plan_status": "AKTIV",
                        "iam_org_level": "Einrichtung",
                    },
                }
            ],
        }
        self.organizations = list(self.user["groups_obj"])
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
        return is_real_facility(group, self.settings.organization_scope_prefix)

    async def organization_groups(self, names=None):
        return [
            group
            for group in self.organizations
            if self._is_organization(group) and (names is None or group["name"] in names)
        ]

    async def organization_group(self, group_uuid):
        for group in self.organizations:
            if group["pk"] == group_uuid and self._is_organization(group):
                return group
        raise AuthentikError(400, "Die gewählte Gruppe ist keine freigegebene Einrichtung.")

    async def employee(self, user_pk):
        assert user_pk == self.user["pk"]
        return self.user

    async def user_record(self, user_pk):
        assert user_pk == self.user["pk"]
        return self.user

    async def employee_by_username(self, username):
        assert username == self.user["username"]
        return self.user

    async def shared_handset_account(self, user_pk):
        assert user_pk == self.user["pk"]
        if not is_shared_handset_account(self.user):
            raise AuthentikError(403, "Dieses Gruppenkonto ist nicht freigegeben.")
        return self.user

    async def access_group_by_name(self, _):
        return self.existing_group

    async def access_groups(self):
        return [self.existing_group] if self.existing_group is not None else []

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

    async def update_device_assignment(
        self, device_uuid, display_name, mode, assigned_to, *, handset_profile=None
    ):
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
        if handset_profile is not None:
            device["attributes"][HANDSET_PROFILE_ATTRIBUTE] = handset_profile
            device["attributes"]["mission-leben.de/device-ownership"] = "company"
        self.updated_device_assignments.append(
            (device_uuid, display_name, mode, assigned_to)
        )
        return device

    async def ensure_login_approval_device(self, username, subject):
        self.login_approval_devices.append((username, subject))

    async def create_enrollment_token(self, name, access_group_uuid, expires):
        self.token_record["expires"] = expires.isoformat()
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
    effective_groups = frozenset({"BR_IT_MANAGEMENT"}) if role == Role.IT else frozenset()
    return Actor("actor-id", "leitung.test", "Leitung Test", roles, organizations, effective_groups)


def organization(pk, name, level="Einrichtung"):
    return {
        "pk": pk,
        "name": name,
        "attributes": {
            "iam_group_type": "organization_unit",
            "iam_managed": True,
            "iam_plan_status": "AKTIV",
            "iam_org_level": level,
        },
    }


@pytest.mark.asyncio
async def test_shared_handset_preflight_is_it_only_and_does_not_issue_a_token(settings):
    authentik = FakeAuthentik(settings)
    calls = []

    async def candidates(search):
        calls.append(("search", search))
        return [{"pk": 17, "username": "team.example"}]

    async def target(user_pk):
        calls.append(("target", user_pk))
        return {"pk": 17, "username": "team.example"}

    authentik.shared_handset_accounts = candidates
    authentik.shared_handset_account = target
    service = EnrollmentService(settings, authentik)

    for role in (Role.EL, Role.PDL):
        with pytest.raises(AuthentikError) as error:
            await service.shared_handset_accounts_for(actor(role), "team")
        assert error.value.status == 403
        with pytest.raises(AuthentikError) as error:
            await service.shared_handset_account_for(actor(role), 17)
        assert error.value.status == 403

    mapped_it_without_canonical_group = Actor(
        "actor-id", "other.it", "Other IT", frozenset({Role.IT}), frozenset(), frozenset({"BR_OTHER"})
    )
    with pytest.raises(AuthentikError) as error:
        await service.shared_handset_accounts_for(mapped_it_without_canonical_group, "team")
    assert error.value.status == 403

    it = actor(Role.IT)
    assert (await service.shared_handset_accounts_for(it, "team"))[0]["pk"] == 17
    assert (await service.shared_handset_account_for(it, 17))["username"] == "team.example"
    assert calls == [("search", "team"), ("target", 17)]
    assert authentik.created_groups == []
    assert authentik.created_bindings == []
    assert authentik.audit_events == []


@pytest.mark.asyncio
async def test_it_enrolls_one_company_handset_for_interactive_shared_mailbox(settings):
    authentik = FakeAuthentik(settings)
    authentik.user["username"] = "haus042"
    authentik.user["attributes"] = {
        "iam_account_kind": "shared",
        "iam_directory_class": "mailbox",
        "iam_interactive_login_allowed": True,
        "iam_noninteractive_account": False,
    }
    service = EnrollmentService(settings, authentik)

    for enrolling_actor in (actor(Role.EL), actor(Role.PDL)):
        with pytest.raises(AuthentikError) as error:
            await service.issue_shared_handset(enrolling_actor, 42)
        assert error.value.status == 403
    assert authentik.created_groups == []

    issued = await service.issue_shared_handset(actor(Role.IT), 42)
    assert issued.mode == "personal"
    assert authentik.created_groups[0]["name"] == "Mission Leben Android - Personal - haus042"
    assert authentik.existing_group["attributes"][HANDSET_PROFILE_ATTRIBUTE] == "shared-account"
    assert authentik.existing_group["attributes"]["mission-leben.de/device-ownership"] == "company"
    assert authentik.created_bindings == [("user", authentik.token_record["device_group"], 42)]
    assert authentik.login_approval_devices == []

    authentik.token_record["device_group_obj"] = authentik.existing_group
    authentik._bindings = [{
        "enabled": True, "negate": False, "policy": None, "user": 42, "group": None,
    }]
    result = await service.redeem(
        authentik.token_record["token_uuid"],
        "abcdefghijklmnopqrstuvwxyz0123456789_-",
        "personal",
        "ml-android-1234567890abcdef",
        "Samsung Diensthandy",
        profile_supported=True,
    )
    assert result["enrollment_profile"] == "shared-account-handset"
    assert authentik.device_records[-1]["attributes"][HANDSET_PROFILE_ATTRIBUTE] == "shared-account"
    assert authentik.device_records[-1]["attributes"]["mission-leben.de/device-ownership"] == "company"
    assert await service.device_status("agent-device-token") == {
        "device_id": authentik.device_uuid,
        "trusted": True,
        "enrollment_profile": "shared-account-handset",
    }
    authentik.device_records.append({
        "device_uuid": "ffffffff-bbbb-cccc-dddd-eeeeeeeeeeee",
        "name": "Zweites Handy",
        "access_group": authentik.token_record["device_group"],
        "expiring": False,
        "expires": None,
        "attributes": {"mission-leben.de/status": "active"},
    })
    with pytest.raises(AuthentikError) as error:
        await service.device_status("agent-device-token")
    assert error.value.status == 403


def test_it_handset_page_requires_person_recipient_and_hides_enrollment_link(settings, monkeypatch):
    settings = replace(settings, smtp_host="mail.mission-leben.de", smtp_sender="it-service@mission-leben.de")
    authentik = FakeAuthentik(settings)
    person = dict(authentik.user)
    authentik.user["username"] = "haus042"
    authentik.user["attributes"] = {
        "iam_account_kind": "shared",
        "iam_directory_class": "mailbox",
        "iam_interactive_login_allowed": True,
        "iam_noninteractive_account": False,
    }

    async def accounts(_search):
        return [authentik.user]

    authentik.shared_handset_accounts = accounts
    async def people(_search, _allowed_groups):
        return [person]

    async def employee(pk):
        assert pk == person["pk"]
        return person

    sent = []
    async def fake_send(_settings, recipient, subject, body):
        sent.append((recipient, subject, body))

    authentik.employees = people
    authentik.employee = employee
    monkeypatch.setattr("mission_leben_device_enrollment.app.send_setup_mail", fake_send)
    app = create_app(settings, authentik)
    headers = {
        "x-authentik-meta-app": settings.proxy_app_slug,
        "x-authentik-uid": "actor-id",
        "x-authentik-username": "leitung.test",
        "x-authentik-name": "Leitung Test",
        "x-authentik-groups": "BR_IT_MANAGEMENT",
    }
    csrf_token = CsrfProtector(settings.csrf_secret, settings.public_origin).issue(
        actor(Role.IT), "issue-shared-handset"
    )

    with TestClient(app) as client:
        page = client.get("/handset?q=haus", headers=headers)
        assert page.status_code == 200
        assert "E-Mail-Empfänger suchen" in page.text
        assert 'name="recipient_email"' not in page.text
        assert 'name="delivery"' not in page.text
        assert "Die Nutzung auf einem Tablet" not in page.text
        assert "Ich bestätige" not in page.text
        assert 'name="company_owned"' not in page.text

        recipient_page = client.get("/handset/recipient?account_pk=42&q=maria", headers=headers)
        assert recipient_page.status_code == 200
        assert person["email"] in recipient_page.text
        assert "Gruppenkonto: " in recipient_page.text
        assert not authentik.audit_events

        response = client.post(
            "/handset/enrollments",
            headers={**headers, "origin": settings.public_origin},
            data={"account_pk": "42", "recipient_pk": "42", "csrf_token": csrf_token},
        )
        assert response.status_code == 200
        assert "Einrichtungslink versendet" in response.text
        assert 'id="copy-enrollment-link"' not in response.text
        assert "#token=" not in response.text
        assert sent[0][0] == person["email"]
        assert "Gruppenkonto" in sent[0][2]
        assert authentik.existing_group["attributes"]["mission-leben.de/device-ownership"] == "company"


def test_it_handset_mail_requires_corporate_recipient(settings, monkeypatch):
    settings = replace(settings, smtp_host="mail.mission-leben.de", smtp_sender="it-service@mission-leben.de")
    authentik = FakeAuthentik(settings)
    authentik.user["username"] = "haus042"
    authentik.user["attributes"] = {
        "iam_account_kind": "shared",
        "iam_directory_class": "mailbox",
        "iam_interactive_login_allowed": True,
        "iam_noninteractive_account": False,
    }
    person = {
        **authentik.user,
        "email": "private@example.org",
        "attributes": {"iam_account_kind": "person", "iam_directory_class": "person"},
    }

    async def employee(_pk):
        return person

    authentik.employee = employee
    sent = []

    async def fake_send(_settings, recipient, subject, body):
        sent.append((recipient, subject, body))

    monkeypatch.setattr("mission_leben_device_enrollment.app.send_setup_mail", fake_send)
    app = create_app(settings, authentik)
    headers = {
        "x-authentik-meta-app": settings.proxy_app_slug,
        "x-authentik-uid": "actor-id",
        "x-authentik-username": "leitung.test",
        "x-authentik-name": "Leitung Test",
        "x-authentik-groups": "BR_IT_MANAGEMENT",
        "origin": settings.public_origin,
    }
    csrf_token = CsrfProtector(settings.csrf_secret, settings.public_origin).issue(
        actor(Role.IT), "issue-shared-handset"
    )
    with TestClient(app) as client:
        bad = client.post(
            "/handset/enrollments", headers=headers,
            data={"account_pk": "42", "recipient_pk": "42", "csrf_token": csrf_token},
        )
        assert bad.status_code == 400
        assert not authentik.audit_events
        person["email"] = "dienst@mission-leben.de"
        good = client.post(
            "/handset/enrollments", headers=headers,
            data={"account_pk": "42", "recipient_pk": "42", "csrf_token": csrf_token},
        )
        assert good.status_code == 200
        assert sent[0][0] == "dienst@mission-leben.de"
        assert "https://geraete.example.org/setup#token=" in sent[0][2]


def test_handset_mail_failure_never_displays_registration_secret(settings, monkeypatch):
    settings = replace(settings, smtp_host="mail.mission-leben.de", smtp_sender="it-service@mission-leben.de")
    authentik = FakeAuthentik(settings)
    person = dict(authentik.user)
    authentik.user["attributes"] = {
        "iam_account_kind": "shared",
        "iam_directory_class": "mailbox",
        "iam_interactive_login_allowed": True,
        "iam_noninteractive_account": False,
    }

    async def employee(_pk):
        return person

    async def failed_send(_settings, _recipient, _subject, _body):
        raise RuntimeError("synthetic SMTP failure")

    authentik.employee = employee
    monkeypatch.setattr("mission_leben_device_enrollment.app.send_setup_mail", failed_send)
    app = create_app(settings, authentik)
    headers = {
        "x-authentik-meta-app": settings.proxy_app_slug,
        "x-authentik-uid": "actor-id",
        "x-authentik-username": "leitung.test",
        "x-authentik-name": "Leitung Test",
        "x-authentik-groups": "BR_IT_MANAGEMENT",
        "origin": settings.public_origin,
    }
    csrf_token = CsrfProtector(settings.csrf_secret, settings.public_origin).issue(
        actor(Role.IT), "issue-shared-handset"
    )
    with TestClient(app) as client:
        response = client.post(
            "/handset/enrollments", headers=headers,
            data={"account_pk": "42", "recipient_pk": "42", "csrf_token": csrf_token},
        )
        assert response.status_code == 502
        assert "#token=" not in response.text
        assert 'id="copy-enrollment-link"' not in response.text


@pytest.mark.asyncio
async def test_el_sees_all_own_real_facilities_but_not_a_subgroup(settings):
    authentik = FakeAuthentik(settings)
    authentik.organizations = [
        organization("10000000-bbbb-cccc-dddd-eeeeeeeeeeee", "ORG_ML_H015"),
        organization("20000000-bbbb-cccc-dddd-eeeeeeeeeeee", "ORG_ML_H016"),
        organization(
            "30000000-bbbb-cccc-dddd-eeeeeeeeeeee",
            "ORG_ML_H031_01",
            "Einrichtung/Teilbetrieb",
        ),
    ]
    current = actor(
        organizations=frozenset({"ORG_ML_H015", "ORG_ML_H016", "ORG_ML_H031_01"})
    )

    groups = await EnrollmentService(settings, authentik).organizations_for(current)

    assert [group["name"] for group in groups] == ["ORG_ML_H015", "ORG_ML_H016"]


@pytest.mark.asyncio
async def test_it_sees_only_real_facilities(settings):
    authentik = FakeAuthentik(settings)
    authentik.organizations = [
        organization("10000000-bbbb-cccc-dddd-eeeeeeeeeeee", "ORG_ML_H001", "Geschäftseinheit/Standort"),
        organization("20000000-bbbb-cccc-dddd-eeeeeeeeeeee", "ORG_ML_H042"),
        organization("30000000-bbbb-cccc-dddd-eeeeeeeeeeee", "ORG_ML_ZD_IT", "Teilbereich"),
    ]

    groups = await EnrollmentService(settings, authentik).organizations_for(
        actor(Role.IT, frozenset())
    )

    assert [group["name"] for group in groups] == ["ORG_ML_H001", "ORG_ML_H042"]


@pytest.mark.asyncio
async def test_shared_tablet_binds_exactly_one_revalidated_facility(settings):
    authentik = FakeAuthentik(settings)

    issued = await EnrollmentService(settings, authentik).issue_shared(
        actor(),
        authentik.organizations[0]["pk"],
        "Wohnbereich 1",
    )

    assert issued.mode == "shared"
    assert authentik.created_groups[0]["name"] == (
        "Mission Leben Android - Shared - ORG_ML_H042"
    )
    assert authentik.created_groups[0]["attributes"]["mission-leben.de/facility-group"] == (
        "ORG_ML_H042"
    )
    assert authentik.created_bindings == [
        ("group", "dddddddd-bbbb-cccc-dddd-eeeeeeeeeeee", authentik.organizations[0]["pk"])
    ]


@pytest.mark.asyncio
async def test_scoped_initializer_cannot_select_another_facility(settings):
    authentik = FakeAuthentik(settings)

    with pytest.raises(AuthentikError) as error:
        await EnrollmentService(settings, authentik).issue_shared(
            actor(organizations=frozenset({"ORG_ML_H015"})),
            authentik.organizations[0]["pk"],
            "Wohnbereich 1",
        )

    assert error.value.status == 403
    assert authentik.created_groups == []


@pytest.mark.asyncio
async def test_personal_enrollment_is_bound_before_qr_is_issued(settings):
    authentik = FakeAuthentik(settings)
    service = EnrollmentService(settings, authentik)

    issued_at = datetime.now(UTC)
    issued = await service.issue_personal(actor(), 42)

    assert timedelta(minutes=30) <= issued.expires - issued_at < timedelta(minutes=30, seconds=5)
    assert authentik.created_groups[0]["name"] == (
        "Mission Leben Android - Personal - m.beispiel"
    )
    assert authentik.created_groups[0]["attributes"]["mission-leben.de/user-uuid"] == authentik.user["uuid"]
    assert authentik.created_bindings == [
        ("user", "dddddddd-bbbb-cccc-dddd-eeeeeeeeeeee", 42)
    ]
    assert authentik.login_approval_devices == [
        (authentik.user["username"], authentik.user["uid"])
    ]
    pending_until = datetime.fromisoformat(
        authentik.existing_group["attributes"][PENDING_ENROLLMENT_ATTRIBUTE].replace(
            "Z", "+00:00"
        )
    )
    assert pending_until > datetime.now(UTC)
    assert "token_id=" in issued.deep_link()
    assert "mode=personal" in issued.deep_link()
    assert issued.install_link(settings.public_origin).startswith(
        "https://geraete.example.org/install#token="
    )
    split_install_link = urlsplit(issued.install_link(settings.public_origin))
    assert split_install_link.query == ""
    assert "token=abcdefghijklmnopqrstuvwxyz0123456789_-" in split_install_link.fragment
    assert authentik.audit_events[0][0] == "model_created"


def test_enrollment_lifetime_is_bounded_to_thirty_minutes(settings):
    assert settings.token_ttl_seconds == 1800
    replace(settings, token_ttl_seconds=120).validate()
    replace(settings, token_ttl_seconds=1800).validate()
    with pytest.raises(RuntimeError, match="between 120 and 1800"):
        replace(settings, token_ttl_seconds=1801).validate()


@pytest.mark.asyncio
async def test_personal_enrollment_renames_uuid_group_without_creating_a_duplicate(settings):
    authentik = FakeAuthentik(settings)
    authentik.existing_group = {
        "pbm_uuid": "dddddddd-bbbb-cccc-dddd-eeeeeeeeeeee",
        "name": "Mission Leben Android - Personal - " + authentik.user["uuid"],
        "attributes": {
            "mission-leben.de/purpose": "android-portal",
            "mission-leben.de/status": "active",
            "mission-leben.de/mode": "personal",
            "mission-leben.de/user-uuid": authentik.user["uuid"],
            "mission-leben.de/username": authentik.user["username"],
        },
    }

    await EnrollmentService(settings, authentik).issue_personal(actor(), 42)

    assert authentik.created_groups == []
    assert authentik.existing_group["name"] == (
        "Mission Leben Android - Personal - m.beispiel"
    )


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
    authentik.token_record["device_group_obj"]["attributes"][
        PENDING_ENROLLMENT_ATTRIBUTE
    ] = (datetime.now(UTC) + timedelta(minutes=5)).isoformat()
    service = EnrollmentService(settings, authentik)
    result = await service.redeem(
        authentik.token_record["token_uuid"],
        "abcdefghijklmnopqrstuvwxyz0123456789_-",
        "personal",
        "ml-android-1234567890abcdef",
        "TCL T807D (12345678)",
    )

    assert result == {
        "token": "agent-device-token",
        "enrollment_profile": "personal-employee",
    }
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
    assert PENDING_ENROLLMENT_ATTRIBUTE not in authentik.existing_group["attributes"]
    assert authentik.audit_events[-1][0] == "model_updated"


@pytest.mark.asyncio
async def test_special_handset_token_is_rejected_before_agent_enrollment(settings):
    authentik = FakeAuthentik(settings)
    authentik.token_record["device_group_obj"]["attributes"][HANDSET_PROFILE_ATTRIBUTE] = (
        "shared-account"
    )
    service = EnrollmentService(settings, authentik)

    with pytest.raises(AuthentikError) as error:
        await service.redeem(
            authentik.token_record["token_uuid"],
            "abcdefghijklmnopqrstuvwxyz0123456789_-",
            "personal",
            "ml-android-1234567890abcdef",
            "Diensthandy",
        )
    assert error.value.status == 403
    assert authentik.enrolled == []
    assert authentik.deleted_tokens == []


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

    assert result == {
        "token": "agent-device-token",
        "enrollment_profile": "facility-tablet",
    }
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


def test_management_setup_has_download_first_and_registration_after_confirmation(settings):
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
        assert f'href="{settings.public_origin}/download"' in home.text
        assert settings.apk_download_url not in home.text
        assert "<svg" in home.text
        assert "default-src 'none'" in home.headers["content-security-policy"]
        assert home.headers["referrer-policy"] == "same-origin"

        response = client.post(
            "/personal/enrollments",
            headers={**headers, "origin": settings.public_origin},
            data={"employee_pk": "42", "csrf_token": csrf_token},
        )
        assert response.status_code == 200
        assert "Erst installieren, dann registrieren" in response.text
        assert response.text.index("Handy vorbereiten") < response.text.index("App installieren")
        assert response.text.index("App installieren") < response.text.index("Gerät registrieren")
        assert "App installiert" in response.text
        assert settings.apk_download_url in response.text
        assert "<svg" in response.text
        assert 'id="copy-enrollment-link"' in response.text
        assert 'data-link="https://geraete.example.org/setup#token=' in response.text
        assert response.text.count("abcdefghijklmnopqrstuvwxyz0123456789_-") == 1
        assert 'src="/static/setup.js"' in response.text
        assert "script-src 'self'" in response.headers["content-security-policy"]
        assert "connect-src 'self'" in response.headers["content-security-policy"]
        assert response.headers["cache-control"] == "no-store"
        setup_script = client.get("/static/setup.js")
        assert setup_script.status_code == 200
        assert "navigator.clipboard.writeText" in setup_script.text

        shared_setup = client.get("/setup#fragment-is-not-sent")
        assert shared_setup.status_code == 200
        assert settings.apk_download_url in shared_setup.text
        assert "abcdefghijklmnopqrstuvwxyz0123456789_-" not in shared_setup.text
        assert "script-src 'self'" in shared_setup.headers["content-security-policy"]

        self_install = client.get("/download")
        assert self_install.status_code == 200
        assert "App selbst installieren" in self_install.text
        assert "App öffnen und anmelden" in self_install.text
        assert "Du brauchst keinen zweiten QR-Code" in self_install.text
        assert settings.apk_download_url in self_install.text
        assert "script-src 'self'" in self_install.headers["content-security-policy"]

        preview = client.post("/api/v1/setup/qr", json={
            "token_uuid": authentik.token_record["token_uuid"],
            "token": "abcdefghijklmnopqrstuvwxyz0123456789_-",
            "mode": "personal",
        })
        assert preview.status_code == 200
        assert preview.json()["image"].startswith("data:image/png;base64,")
        assert "expires_at" in preview.json()
        assert "no-store" in preview.headers["cache-control"]
        bad_token = client.post("/api/v1/setup/qr", json={
            "token_uuid": authentik.token_record["token_uuid"],
            "token": "abcdefghijklmnopqrstuvwxyz0123456789_x",
            "mode": "personal",
        })
        assert bad_token.status_code == 401
        wrong_mode = client.post("/api/v1/setup/qr", json={
            "token_uuid": authentik.token_record["token_uuid"],
            "token": "abcdefghijklmnopqrstuvwxyz0123456789_-",
            "mode": "shared",
        })
        assert wrong_mode.status_code == 403
        authentik.token_record["expires"] = (datetime.now(UTC) - timedelta(seconds=1)).isoformat()
        expired = client.post("/api/v1/setup/qr", json={
            "token_uuid": authentik.token_record["token_uuid"],
            "token": "abcdefghijklmnopqrstuvwxyz0123456789_-",
            "mode": "personal",
        })
        assert expired.status_code == 410
        authentik.token_record["expires"] = (datetime.now(UTC) + timedelta(minutes=5)).isoformat()
        authentik.deleted_tokens.append(authentik.token_record["token_uuid"])
        used = client.post("/api/v1/setup/qr", json={
            "token_uuid": authentik.token_record["token_uuid"],
            "token": "abcdefghijklmnopqrstuvwxyz0123456789_-",
            "mode": "personal",
        })
        assert used.status_code == 404

        installer = client.get("/install#fragment-is-not-sent")
        assert installer.status_code == 200
        assert "App herunterladen" in installer.text
        assert "Samsung Android" in installer.text
        assert "Automatische Sperre" in installer.text
        assert "nach 30 Minuten" in installer.text
        assert "muss für App-Updates ausgeschaltet bleiben" in installer.text
        assert "vorübergehend aus" not in installer.text
        assert "Browser wieder aus" not in installer.text
        assert 'href="/">Zur Startseite</a>' in installer.text
        assert "Mitarbeitende mit TOTP können sich direkt in der App anmelden" in installer.text
        assert 'id="manual-finish"' in installer.text
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


def test_it_can_send_personal_enrollment_and_nonexpiring_totp_download(settings, monkeypatch):
    settings = replace(settings, smtp_host="mail.mission-leben.de", smtp_sender="it-service@mission-leben.de")
    authentik = FakeAuthentik(settings)
    async def people(_search, _allowed_groups):
        return [authentik.user]

    authentik.employees = people
    messages = []

    async def fake_send(_settings, recipient, subject, body):
        messages.append((recipient, subject, body))

    monkeypatch.setattr("mission_leben_device_enrollment.app.send_setup_mail", fake_send)
    app = create_app(settings, authentik)
    headers = {
        "x-authentik-meta-app": settings.proxy_app_slug,
        "x-authentik-uid": "actor-id",
        "x-authentik-username": "leitung.test",
        "x-authentik-name": "Leitung Test",
        "x-authentik-groups": "BR_IT_MANAGEMENT",
        "origin": settings.public_origin,
    }
    csrf_token = CsrfProtector(settings.csrf_secret, settings.public_origin).issue(
        actor(Role.IT), "issue-personal"
    )
    download_csrf = CsrfProtector(settings.csrf_secret, settings.public_origin).issue(
        actor(Role.IT), "send-personal-download"
    )
    with TestClient(app) as client:
        search = client.get("/download/send?q=maria", headers=headers)
        assert search.status_code == 200
        assert "Person suchen" in search.text
        assert "m.beispiel@mission-leben.de" in search.text
        assert not authentik.audit_events
        mailed_setup = client.post(
            "/personal/enrollments",
            headers=headers,
            data={"employee_pk": "42", "csrf_token": csrf_token, "delivery": "email"},
        )
        assert mailed_setup.status_code == 200
        assert "Einrichtungslink an m.beispiel@mission-leben.de versendet" in mailed_setup.text
        assert messages[0][0] == "m.beispiel@mission-leben.de"
        assert "https://geraete.example.org/setup#token=" in messages[0][2]
        assert "30 Minuten" in messages[0][2]

        count_before = len(authentik.audit_events)
        mailed_download = client.post(
            "/personal/download-email",
            headers=headers,
            data={"employee_pk": "42", "csrf_token": download_csrf},
        )
        assert mailed_download.status_code == 200
        assert len(authentik.audit_events) == count_before
        assert "https://geraete.example.org/download" in messages[1][2]
        assert "keinen Registrierungscode" in messages[1][2]
        assert "#token=" not in messages[1][2]

        el_headers = {**headers, "x-authentik-groups": "BR_EINRICHTUNGSLEITUNG|ORG_ML_H042"}
        el_csrf = CsrfProtector(settings.csrf_secret, settings.public_origin).issue(
            actor(), "send-personal-download"
        )
        blocked_search = client.get("/download/send?q=maria", headers=el_headers)
        assert blocked_search.status_code == 403
        denied = client.post(
            "/personal/download-email",
            headers=el_headers,
            data={"employee_pk": "42", "csrf_token": el_csrf},
        )
        assert denied.status_code == 403
        assert len(messages) == 2

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
        assert active.json() == {
            "device_id": authentik.device_uuid,
            "trusted": True,
            "enrollment_profile": "personal-employee",
        }

        authentik.token_record["device_group_obj"]["attributes"][HANDSET_PROFILE_ATTRIBUTE] = (
            "shared-account"
        )
        not_released = client.get(
            "/api/v1/devices/status",
            headers={"authorization": "Bearer+Agent abcdefghijklmnopqrstuvwxyz0123456789_-"},
        )
        assert not_released.status_code == 403
        authentik.token_record["device_group_obj"]["attributes"].pop(HANDSET_PROFILE_ATTRIBUTE)

        authentik.device_records[0]["attributes"]["mission-leben.de/status"] = "disabled"
        disabled = client.get(
            "/api/v1/devices/status",
            headers={"authorization": "Bearer+Agent abcdefghijklmnopqrstuvwxyz0123456789_-"},
        )
        assert disabled.status_code == 403
        assert disabled.json() == {"error": "Das Gerät ist deaktiviert oder abgelaufen."}


def test_device_status_rejects_marked_shared_access_group(settings):
    authentik = FakeAuthentik(settings)
    authentik.token_record["device_group_obj"]["attributes"] = {
        "mission-leben.de/purpose": "android-portal",
        "mission-leben.de/mode": "shared",
        HANDSET_PROFILE_ATTRIBUTE: "shared-account",
    }
    authentik.device_records.append(
        {
            "device_uuid": authentik.device_uuid,
            "name": "Testtablet",
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
        assert "kein Diensthandy-Profil" in response.json()["error"]


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


def test_device_status_rejects_non_personal_bound_account(settings):
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
    authentik.user["attributes"]["iam_account_kind"] = "shared"
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
    assert response.json() == {
        "error": "Das zugeordnete Konto passt nicht zum Gerätetyp."
    }


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
        assert "30 Minuten gültig" in page.text

        management = client.get("/", headers=headers)
        assert management.status_code == 403

        forbidden_qr = client.post(
            "/personal/enrollments",
            headers={**headers, "origin": settings.public_origin},
            data={"employee_pk": "42", "csrf_token": csrf_token},
        )
        assert forbidden_qr.status_code == 403
        assert "copy-enrollment-link" not in forbidden_qr.text

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
