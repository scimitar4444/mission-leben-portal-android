from __future__ import annotations

import pytest
from fastapi import HTTPException
from starlette.requests import Request

from mission_leben_device_enrollment.auth import (
    CsrfProtector,
    Role,
    actor_from_request,
    authenticated_actor_from_request,
)


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
        ("BR_EINRICHTUNGSLEITUNG", Role.EL),
        ("BR_PFLEGEDIENSTLEITUNG", Role.PDL),
    ],
)
def test_recognizes_only_configured_operational_roles(settings, role_group, expected_role):
    actor = actor_from_request(request(authentik_headers(f"{role_group}|ORG_ML_H042")), settings)
    assert actor.roles == frozenset({expected_role})
    assert actor.organization_names == frozenset({"ORG_ML_H042"})


@pytest.mark.parametrize(
    "groups",
    [
        "BR_GESCHAEFTSBEREICHSLEITUNG|ORG_ML_H001",
        "BR_GESCHAEFTSEINHEITSLEITUNG|ORG_ML_H001",
        "BR_ABTEILUNGSLEITUNG|ORG_ML_H001",
        "BR_GESCHAEFTSFUEHRUNG|ORG_ML_H001",
    ],
)
def test_central_leadership_is_not_an_initializer(settings, groups):
    with pytest.raises(HTTPException) as error:
        actor_from_request(request(authentik_headers(groups)), settings)
    assert error.value.status_code == 403


def test_it_has_global_scope_without_organization(settings):
    actor = actor_from_request(request(authentik_headers("BR_IT_MANAGEMENT")), settings)
    assert actor.has_global_scope
    assert actor.can_initialize_shared_handset


def test_shared_handset_role_requires_exact_canonical_it_group(settings):
    from dataclasses import replace

    configured = replace(settings, role_it_groups=("BR_OTHER_IT",))
    actor = actor_from_request(request(authentik_headers("BR_OTHER_IT")), configured)
    assert actor.has_global_scope
    assert not actor.can_initialize_shared_handset


def test_scoped_roles_require_an_explicit_managed_organization(settings):
    for role_group in (
        "BR_PFLEGEDIENSTLEITUNG",
        "BR_EINRICHTUNGSLEITUNG",
    ):
        with pytest.raises(HTTPException) as error:
            actor_from_request(request(authentik_headers(role_group)), settings)
        assert error.value.status_code == 403


def test_unrelated_legacy_or_gf_group_has_no_permission(settings):
    for groups in (
        "Mitarbeitende|ORG_ML_H042",
        "ML_DEVICE_INIT_EL|ORG_ML_H042",
        "ENT_DEVICE_INITIALIZE_EL|ORG_ML_H042",
        "BR_GESCHAEFTSFUEHRUNG|ORG_ML_H001",
    ):
        with pytest.raises(HTTPException) as error:
            actor_from_request(request(authentik_headers(groups)), settings)
        assert error.value.status_code == 403


def test_el_and_pdl_keep_all_effective_facilities_deduplicated(settings):
    actor = actor_from_request(
        request(
            authentik_headers(
                "BR_PFLEGEDIENSTLEITUNG|BR_EINRICHTUNGSLEITUNG|"
                "ORG_ML_H015|ORG_ML_H016|ORG_ML_H015|ORG_ML_ZD_IT"
            )
        ),
        settings,
    )
    assert actor.roles == frozenset({Role.EL, Role.PDL})
    assert actor.organization_names == frozenset({"ORG_ML_H015", "ORG_ML_H016"})
    assert actor.role_label == "EL / PDL"


def test_similarly_named_noncanonical_org_group_is_not_a_scope(settings):
    with pytest.raises(HTTPException) as error:
        actor_from_request(
            request(authentik_headers("BR_EINRICHTUNGSLEITUNG|ORG_ML_HACKER")), settings
        )
    assert error.value.status_code == 403


def test_normal_employee_is_authenticated_only_for_self_service(settings):
    headers = authentik_headers("Mitarbeitende|ORG_ML_H042")
    self_actor = authenticated_actor_from_request(request(headers), settings)
    assert not self_actor.roles
    assert not self_actor.can_manage_devices
    with pytest.raises(HTTPException) as error:
        actor_from_request(request(headers), settings)
    assert error.value.status_code == 403


def test_display_name_decodes_utf8_from_authentik_proxy_header(settings):
    headers = authentik_headers("Mitarbeitende|ORG_ML_H042")
    headers["x-authentik-name"] = "Umlaut Ää Öö Üü ß"

    actor = authenticated_actor_from_request(request(headers), settings)

    assert actor.display_name == "Umlaut Ää Öö Üü ß"


def test_proxy_app_header_is_mandatory(settings):
    headers = authentik_headers("BR_IT_MANAGEMENT")
    headers["x-authentik-meta-app"] = "another-app"
    with pytest.raises(HTTPException) as error:
        actor_from_request(request(headers), settings)
    assert error.value.status_code == 401


def test_csrf_is_actor_action_and_origin_bound(settings):
    actor = actor_from_request(
        request(authentik_headers("BR_EINRICHTUNGSLEITUNG|ORG_ML_H042")), settings
    )
    csrf = CsrfProtector(settings.csrf_secret, settings.public_origin)
    token = csrf.issue(actor, "issue-personal", now=1_800_000_000)
    valid_request = request({"origin": settings.public_origin})
    # Use the same time bucket without patching wall clock by verifying a fresh token.
    fresh = csrf.issue(actor, "issue-personal")
    csrf.verify_request(valid_request, actor, "issue-personal", fresh)
    csrf.verify_request(
        request({"referer": settings.public_origin + "/personal"}),
        actor,
        "issue-personal",
        fresh,
    )

    with pytest.raises(HTTPException):
        csrf.verify_request(request({"origin": "https://evil.example"}), actor, "issue-personal", fresh)
    with pytest.raises(HTTPException):
        csrf.verify_request(valid_request, actor, "issue-shared", fresh)
    assert token != csrf.issue(actor, "issue-shared", now=1_800_000_000)
