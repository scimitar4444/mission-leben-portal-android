from __future__ import annotations

import asyncio
from copy import deepcopy
from dataclasses import replace
from datetime import UTC, datetime, timedelta

import pytest
from fastapi.testclient import TestClient

from mission_leben_device_enrollment.app import create_app
from mission_leben_device_enrollment.auth import Actor, CsrfProtector, Role
from mission_leben_device_enrollment.authentik import AuthentikError
from mission_leben_device_enrollment.service import (
    EnrollmentService, PERSONAL_DEVICE_LIMIT, PERSONAL_INTENT_PREFIX,
)
from test_service import FakeAuthentik, actor


FIRST = "11111111-1111-1111-1111-111111111111"
SECOND = "22222222-2222-2222-2222-222222222222"
KEY = "abcdefghijklmnopqrstuvwxyz0123456789_-"


def existing(authentik, device_id, *, disabled=False):
    device = {"device_uuid": device_id, "name": "Handy " + device_id[:4],
              "access_group": authentik.token_record["device_group"],
              "access_group_obj": authentik.token_record["device_group_obj"],
              "expiring": False, "expires": None, "facts": None,
              "attributes": {"serial": "ml-android-" + device_id.replace("-", ""),
                             "mission-leben.de/status": "disabled" if disabled else "active"}}
    authentik.device_records.append(device)
    authentik.existing_group = authentik.token_record["device_group_obj"]
    return device


async def redeem(service, authentik, *, token_uuid=None, serial="ml-android-abcdef0123456789"):
    return await service.redeem(token_uuid or authentik.token_record["token_uuid"], KEY,
                                "personal", serial, "Neues Handy")


@pytest.mark.asyncio
async def test_second_employee_phone_preserves_first_and_records_add_intent(settings):
    authentik = FakeAuthentik(settings)
    existing(authentik, FIRST)
    service = EnrollmentService(settings, authentik)
    issued = await service.issue_personal(actor(), 42, "add")
    assert issued.replaced_devices == ()
    assert issued.personal_action == "add"
    assert authentik.token_record["name"] == PERSONAL_INTENT_PREFIX + "add"
    await redeem(service, authentik)
    assert authentik.disabled_devices == []
    assert len(await service._active_devices(authentik.token_record["device_group"])) == 2
    assert authentik.audit_events[-1][2]["replaced_device_uuids"] == []
    assert (await service.device_status("agent-device-token"))["trusted"] is True


@pytest.mark.asyncio
async def test_replacing_one_of_two_preserves_the_other(settings):
    authentik = FakeAuthentik(settings)
    existing(authentik, FIRST)
    peer = existing(authentik, SECOND)
    service = EnrollmentService(settings, authentik)
    issued = await service.issue_personal(actor(), 42, "replace:" + FIRST)
    assert [device.device_uuid for device in issued.replaced_devices] == [FIRST]
    await redeem(service, authentik)
    assert authentik.disabled_devices == [FIRST]
    assert peer["attributes"]["mission-leben.de/status"] == "active"
    assert len(await service._active_devices(authentik.token_record["device_group"])) == 2
    assert authentik.audit_events[-1][2]["replaced_device_uuids"] == [FIRST]


@pytest.mark.asyncio
@pytest.mark.parametrize("self_service", [False, True])
async def test_two_existing_phones_require_specific_replacement(settings, self_service):
    authentik = FakeAuthentik(settings)
    existing(authentik, FIRST)
    existing(authentik, SECOND)
    service = EnrollmentService(settings, authentik)
    for choice in ("add", "", "replace:33333333-3333-3333-3333-333333333333"):
        with pytest.raises(AuthentikError):
            if self_service:
                current = Actor("self-id", authentik.user["username"], "Mitarbeiter", frozenset(), frozenset())
                await service.issue_self_personal(current, choice)
            else:
                await service.issue_personal(actor(), 42, choice)
    assert authentik.audit_events == []
    assert authentik.disabled_devices == []
    assert authentik.enrolled == []


@pytest.mark.asyncio
async def test_missing_action_on_cached_old_page_does_not_replace_existing_phone(settings):
    authentik = FakeAuthentik(settings)
    existing(authentik, FIRST)
    with pytest.raises(AuthentikError):
        await EnrollmentService(settings, authentik).issue_personal(actor(), 42)
    assert authentik.disabled_devices == []


@pytest.mark.asyncio
async def test_quota_rechecked_after_add_link_was_issued(settings):
    authentik = FakeAuthentik(settings)
    existing(authentik, FIRST)
    service = EnrollmentService(settings, authentik)
    await service.issue_personal(actor(), 42, "add")
    existing(authentik, SECOND)
    with pytest.raises(AuthentikError) as error:
        await redeem(service, authentik)
    assert error.value.status == 409
    assert authentik.enrolled == []
    assert authentik.deleted_tokens == []
    assert authentik.disabled_devices == []


@pytest.mark.asyncio
async def test_expired_or_disabled_devices_do_not_use_a_slot(settings):
    authentik = FakeAuthentik(settings)
    existing(authentik, FIRST, disabled=True)
    old = existing(authentik, SECOND)
    old.update(expiring=True, expires=(datetime.now(UTC) - timedelta(minutes=1)).isoformat())
    service = EnrollmentService(settings, authentik)
    await service.issue_personal(actor(), 42, "add")
    await redeem(service, authentik)
    assert len(await service._active_devices(authentik.token_record["device_group"])) == 1
    assert authentik.disabled_devices == []


@pytest.mark.asyncio
async def test_stale_replacement_target_does_not_replace_peer(settings):
    authentik = FakeAuthentik(settings)
    selected = existing(authentik, FIRST)
    peer = existing(authentik, SECOND)
    service = EnrollmentService(settings, authentik)
    await service.issue_personal(actor(), 42, "replace:" + FIRST)
    selected["attributes"]["mission-leben.de/status"] = "disabled"
    with pytest.raises(AuthentikError) as error:
        await redeem(service, authentik)
    assert error.value.status == 409
    assert authentik.enrolled == []
    assert peer["attributes"]["mission-leben.de/status"] == "active"


@pytest.mark.asyncio
async def test_old_replace_all_link_cannot_disable_two_phones(settings):
    authentik = FakeAuthentik(settings)
    existing(authentik, FIRST)
    existing(authentik, SECOND)
    with pytest.raises(AuthentikError) as error:
        await redeem(EnrollmentService(settings, authentik), authentik)
    assert error.value.status == 409
    assert authentik.disabled_devices == []
    assert authentik.enrolled == []


@pytest.mark.asyncio
async def test_failed_replacement_keeps_both_old_phones_and_locks_only_new(settings):
    authentik = FakeAuthentik(settings)
    existing(authentik, FIRST)
    existing(authentik, SECOND)
    service = EnrollmentService(settings, authentik)
    await service.issue_personal(actor(), 42, "replace:" + FIRST)
    authentik.fail_disable_for = FIRST
    with pytest.raises(AuthentikError) as error:
        await redeem(service, authentik)
    assert error.value.status == 502
    assert authentik.disabled_devices == [authentik.device_uuid]
    assert {device.device_uuid for device in await service._active_devices(authentik.token_record["device_group"])} == {FIRST, SECOND}


@pytest.mark.asyncio
async def test_two_simultaneous_add_redemptions_share_single_worker_quota_lock(settings):
    class ConcurrentFake(FakeAuthentik):
        async def enrollment_token(self, token_uuid):
            return deepcopy(self.tokens[token_uuid])

        async def enroll_agent(self, token, payload):
            await asyncio.sleep(0)
            return await super().enroll_agent(token, payload)

    authentik = ConcurrentFake(settings)
    existing(authentik, FIRST)
    authentik._bindings = [{"enabled": True, "negate": False, "policy": None, "group": None, "user": 42}]
    authentik.token_record["name"] = PERSONAL_INTENT_PREFIX + "add"
    authentik.tokens = {}
    for token_id in ("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"):
        authentik.tokens[token_id] = {**deepcopy(authentik.token_record), "token_uuid": token_id}
    service = EnrollmentService(settings, authentik)
    results = await asyncio.gather(*(redeem(service, authentik, token_uuid=token_id,
        serial="ml-android-" + token_id.replace("-", "")) for token_id in authentik.tokens), return_exceptions=True)
    assert sum(isinstance(result, dict) for result in results) == 1
    assert sum(isinstance(result, AuthentikError) and result.status == 409 for result in results) == 1
    assert len(await service._active_devices(authentik.token_record["device_group"])) == PERSONAL_DEVICE_LIMIT
    assert authentik.disabled_devices == []


@pytest.mark.asyncio
async def test_group_account_keeps_one_phone_and_no_duo_registration(settings):
    authentik = FakeAuthentik(settings)
    authentik.user["attributes"] = {"iam_account_kind": "shared", "iam_directory_class": "mailbox",
                                    "iam_interactive_login_allowed": True, "iam_noninteractive_account": False}
    service = EnrollmentService(settings, authentik)
    await service.issue_shared_handset(actor(Role.IT), 42)
    authentik.device_records.append({"device_uuid": FIRST, "name": "Gruppenhandy", "access_group": authentik.token_record["device_group"],
                                      "expiring": False, "expires": None, "facts": None, "attributes": {}})
    await service.redeem(authentik.token_record["token_uuid"], KEY, "personal", "ml-android-abcdef0123456789",
                         "Neues Gruppenhandy", profile_supported=True)
    assert authentik.disabled_devices == [FIRST]
    assert len(await service._active_devices(authentik.token_record["device_group"])) == 1
    assert authentik.login_approval_devices == []


@pytest.mark.asyncio
async def test_post_enrollment_quota_backstop_never_disables_an_existing_peer(settings):
    class ExtraDeviceFake(FakeAuthentik):
        async def enroll_agent(self, token, payload):
            result = await super().enroll_agent(token, payload)
            existing(self, SECOND)  # Simulate an out-of-band registration during enrollment.
            return result
    authentik = ExtraDeviceFake(settings)
    existing(authentik, FIRST)
    service = EnrollmentService(settings, authentik)
    await service.issue_personal(actor(), 42, "add")
    with pytest.raises(AuthentikError) as error:
        await redeem(service, authentik)
    assert error.value.status == 502
    assert authentik.disabled_devices == [authentik.device_uuid]
    assert {device.device_uuid for device in await service._active_devices(authentik.token_record["device_group"])} == {FIRST, SECOND}


@pytest.mark.asyncio
@pytest.mark.parametrize("choice", ["add", "replace:" + FIRST])
async def test_new_intent_rechecks_active_employee_binding(settings, choice):
    authentik = FakeAuthentik(settings)
    existing(authentik, FIRST)
    service = EnrollmentService(settings, authentik)
    await service.issue_personal(actor(), 42, choice)
    authentik.user["is_active"] = False
    with pytest.raises(AuthentikError) as error:
        await redeem(service, authentik)
    assert error.value.status == 403
    assert authentik.disabled_devices == []
    assert authentik.enrolled == []


@pytest.mark.asyncio
async def test_device_status_denies_more_than_two_employee_phones(settings):
    authentik = FakeAuthentik(settings)
    service = EnrollmentService(settings, authentik)
    await service.issue_personal(actor(), 42)
    existing(authentik, FIRST)
    existing(authentik, SECOND)
    existing(authentik, authentik.device_uuid)
    with pytest.raises(AuthentikError) as error:
        await service.device_status("agent-device-token")
    assert error.value.status == 403


def self_headers(authentik, settings):
    return {"x-authentik-meta-app": settings.proxy_app_slug, "x-authentik-uid": "self-id",
            "x-authentik-username": authentik.user["username"], "x-authentik-name": authentik.user["name"],
            "x-authentik-groups": "Mitarbeitende"}


@pytest.mark.parametrize("count", [0, 1, 2])
def test_self_service_choices_and_post_are_explicit(settings, count):
    authentik = FakeAuthentik(settings)
    for device_id in (FIRST, SECOND)[:count]:
        existing(authentik, device_id)
    current = Actor("self-id", authentik.user["username"], authentik.user["name"], frozenset(), frozenset())
    csrf = CsrfProtector(settings.csrf_secret, settings.public_origin).issue(current, "issue-self-personal")
    with TestClient(create_app(settings, authentik)) as client:
        headers = self_headers(authentik, settings)
        page = client.get("/self", headers=headers)
        assert page.status_code == 200
        assert ('value="add"' in page.text) == (count < 2)
        assert ('value="replace:' + FIRST + '"' in page.text) == (count > 0)
        response = client.post("/self/enrollments", headers={**headers, "origin": settings.public_origin},
                               data={"csrf_token": csrf, "device_action": "add" if count < 2 else "replace:" + FIRST})
        assert response.status_code == 200
        assert "Gerätecode erstellt" in response.text
        assert authentik.token_record["name"] == PERSONAL_INTENT_PREFIX + ("add" if count < 2 else "replace:" + FIRST)


@pytest.mark.parametrize("count", [1, 2])
@pytest.mark.parametrize("delivery", ["screen", "email"])
def test_admin_choices_are_preserved_for_screen_and_email(settings, monkeypatch, count, delivery):
    settings = replace(settings, smtp_host="mail.example.org", smtp_sender="it-service@mission-leben.de")
    authentik = FakeAuthentik(settings)
    for device_id in (FIRST, SECOND)[:count]:
        existing(authentik, device_id)

    async def employees(_query, _groups):
        return [authentik.user]

    messages = []

    async def send_mail(_settings, recipient, _subject, _body):
        messages.append(recipient)

    authentik.employees = employees
    monkeypatch.setattr("mission_leben_device_enrollment.app.send_setup_mail", send_mail)
    headers = {"x-authentik-meta-app": settings.proxy_app_slug, "x-authentik-uid": "actor-id",
               "x-authentik-username": "leitung.test", "x-authentik-name": "Leitung Test",
               "x-authentik-groups": "BR_IT_MANAGEMENT", "origin": settings.public_origin}
    csrf = CsrfProtector(settings.csrf_secret, settings.public_origin).issue(actor(Role.IT), "issue-personal")
    choice = "add" if count == 1 else "replace:" + FIRST
    with TestClient(create_app(settings, authentik)) as client:
        page = client.get("/personal?q=beispiel", headers=headers)
        assert page.status_code == 200
        assert 'name="device_action"' in page.text
        assert ('value="add"' in page.text) == (count == 1)
        if count == 2:
            assert 'value="replace:' + SECOND + '"' in page.text
        response = client.post("/personal/enrollments", headers=headers,
                               data={"employee_pk": 42, "csrf_token": csrf,
                                     "device_action": choice, "delivery": delivery})
        assert response.status_code == 200
        assert authentik.token_record["name"] == PERSONAL_INTENT_PREFIX + choice
        assert messages == ([authentik.user["email"]] if delivery == "email" else [])
        assert authentik.disabled_devices == []
