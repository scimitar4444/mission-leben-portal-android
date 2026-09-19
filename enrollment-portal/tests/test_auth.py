from __future__ import annotations

import pytest
from fastapi import HTTPException
from starlette.requests import Request

from mission_leben_device_enrollment.auth import CsrfProtector, Role, actor_from_request


def request(headers: dict[str, str]) -> Request:
    return Request(
        {
            "type": "http",
            "method": "GET",
            "path": "/",
            "headers": [(key.lower().encode(), value.encode()) for key, value in headers.items()],
        }
    )


def authentik_headers(groups: str) -> dict[str, str]:
    return {
        "x-authentik-meta-app": "mission-leben-device-init",
        "x-authentik-uid": "stable-actor-id",
        "x-authentik-username": "leitung.test",
        "x-authentik-name": "Leitung Test",
        "x-authentik-groups": groups,
    }


@pytest.mark.parametrize(
    ("role_group", "expected_role"),
    [
        ("ML_DEVICE_INIT_IT", Role.IT),
        ("ML_DEVICE_INIT_ZENTRALE", Role.CENTRAL),
        ("ML_DEVICE_INIT_EL", Role.EL),
        ("ML_DEVICE_INIT_PDL", Role.PDL),
    ],
)
def test_recognizes_only_configured_operational_roles(settings, role_group, expected_role):
    actor = actor_from_request(request(authentik_headers(f"{role_group}|ORG_HAUS_1")), settings)
    assert actor.role is expected_role
    assert actor.organization_names == frozenset({"ORG_HAUS_1"})


def test_it_has_global_scope_without_organization(settings):
    actor = actor_from_request(request(authentik_headers("ML_DEVICE_INIT_IT")), settings)
    assert actor.has_global_scope


def test_pdl_el_and_central_require_an_explicit_organization(settings):
    for role_group in ("ML_DEVICE_INIT_PDL", "ML_DEVICE_INIT_EL", "ML_DEVICE_INIT_ZENTRALE"):
        with pytest.raises(HTTPException) as error:
            actor_from_request(request(authentik_headers(role_group)), settings)
        assert error.value.status_code == 403


def test_unrelated_or_gf_group_has_no_permission(settings):
    for groups in ("Mitarbeitende|ORG_HAUS_1", "ML_DEVICE_INIT_GF|ORG_HAUS_1"):
        with pytest.raises(HTTPException) as error:
            actor_from_request(request(authentik_headers(groups)), settings)
        assert error.value.status_code == 403


def test_proxy_app_header_is_mandatory(settings):
    headers = authentik_headers("ML_DEVICE_INIT_IT")
    headers["x-authentik-meta-app"] = "another-app"
    with pytest.raises(HTTPException) as error:
        actor_from_request(request(headers), settings)
    assert error.value.status_code == 401


def test_csrf_is_actor_action_and_origin_bound(settings):
    actor = actor_from_request(request(authentik_headers("ML_DEVICE_INIT_EL|ORG_HAUS_1")), settings)
    csrf = CsrfProtector(settings.csrf_secret, settings.public_origin)
    token = csrf.issue(actor, "issue-personal", now=1_800_000_000)
    valid_request = request({"origin": settings.public_origin})
    # Use the same time bucket without patching wall clock by verifying a fresh token.
    fresh = csrf.issue(actor, "issue-personal")
    csrf.verify_request(valid_request, actor, "issue-personal", fresh)

    with pytest.raises(HTTPException):
        csrf.verify_request(request({"origin": "https://evil.example"}), actor, "issue-personal", fresh)
    with pytest.raises(HTTPException):
        csrf.verify_request(valid_request, actor, "issue-shared", fresh)
    assert token != csrf.issue(actor, "issue-shared", now=1_800_000_000)
