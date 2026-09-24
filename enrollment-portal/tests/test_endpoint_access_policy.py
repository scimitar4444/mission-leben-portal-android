from __future__ import annotations

import ast
from pathlib import Path
from types import SimpleNamespace
from textwrap import indent

import pytest


BOOTSTRAP = Path(__file__).resolve().parents[2] / "authentik" / "bootstrap_endpoint_devices.py"


def _policy(name: str):
    module = ast.parse(BOOTSTRAP.read_text(encoding="utf-8"))
    expression = None
    for node in module.body:
        if not isinstance(node, ast.Assign):
            continue
        if any(isinstance(target, ast.Name) and target.id == name for target in node.targets):
            expression = ast.literal_eval(node.value)
            break
    assert expression is not None
    expression = expression.replace(
        "from authentik.endpoints.connectors.agent.auth import check_device_policies",
        "check_device_policies = request.check_device_policies",
    )
    expression = expression.replace(
        "from authentik.endpoints.models import DeviceUserBinding",
        "DeviceUserBinding = request.DeviceUserBinding",
    )
    expression = expression.replace(
        "from authentik.stages.authenticator_totp.models import TOTPDevice",
        "TOTPDevice = request.TOTPDevice",
    )
    namespace: dict[str, object] = {}
    exec("def evaluate(request):\n" + indent(expression, "    "), namespace)
    return namespace["evaluate"]


def _request(
    *,
    mode: str,
    account_kind: str,
    directory_class: str = "person",
    active: bool = True,
    user_type: str = "internal",
    device_policy_passes: bool = True,
    handset_profile: str | None = None,
    device_handset_profile: str | None = None,
    company_owned: bool = True,
    group_owned: bool = True,
    device_owned: bool = True,
    interactive: bool = True,
    noninteractive: bool = False,
    group_user_uuid: str | None = None,
    binding_kind: str = "user",
    binding_user_pk: int = 42,
    extra_active_binding: bool = False,
):
    user = SimpleNamespace(
        pk=42,
        uuid="11111111-2222-3333-4444-555555555555",
        is_active=active,
        is_anonymous=False,
        type=user_type,
        attributes={
            "iam_account_kind": account_kind,
            "iam_directory_class": directory_class,
            "iam_interactive_login_allowed": interactive,
            "iam_noninteractive_account": noninteractive,
        },
    )
    access_group = SimpleNamespace(
        attributes={
            "mission-leben.de/mode": mode,
            "mission-leben.de/facility-group": "ORG_ML_H042",
            "mission-leben.de/handset-profile": handset_profile,
            "mission-leben.de/device-ownership": "company" if company_owned and group_owned else None,
            "mission-leben.de/user-uuid": group_user_uuid or user.uuid,
        }
    )
    device = SimpleNamespace(
        is_expired=False,
        attributes={
            "mission-leben.de/status": "active",
            "mission-leben.de/handset-profile": device_handset_profile,
            "mission-leben.de/device-ownership": "company" if company_owned and device_owned else None,
        },
        access_group=access_group,
        facts=SimpleNamespace(
            data={"vendor": {"mission-leben.de/portal": {"mode": mode}}}
        ),
    )
    flow_plan = SimpleNamespace(context={"device": device, "pending_user": user})
    http_request = SimpleNamespace(
        META={
            "HTTP_USER_AGENT": (
                f"Android MissionLebenPortal/1.0 MissionLebenMode/{mode}"
            )
        },
        user=user,
    )
    bindings = [SimpleNamespace(
        negate=False,
        user_id=binding_user_pk if binding_kind == "user" else None,
        group_id="group-id" if binding_kind == "group" else None,
        policy_id="policy-id" if binding_kind == "policy" else None,
    )]
    if extra_active_binding:
        bindings.append(SimpleNamespace(negate=False, user_id=42, group_id=None, policy_id=None))
    return SimpleNamespace(
        http_request=http_request,
        context={"device": device, "flow_plan": flow_plan},
        check_device_policies=lambda *_: SimpleNamespace(
            passing=device_policy_passes
        ),
        DeviceUserBinding=SimpleNamespace(objects=SimpleNamespace(filter=lambda **_: bindings)),
        TOTPDevice=SimpleNamespace(objects=SimpleNamespace(
            filter=lambda **_: SimpleNamespace(exists=lambda: True)
        )),
    )


@pytest.mark.parametrize(
    "policy_name",
    ["DENY_DEVICE_ACCESS_EXPRESSION", "DENY_OIDC_DEVICE_ACCESS_EXPRESSION"],
)
@pytest.mark.parametrize(
    ("request_kwargs", "denied"),
    [
        ({"mode": "personal", "account_kind": "person"}, False),
        ({"mode": "personal", "account_kind": "shared"}, True),
        ({"mode": "personal", "account_kind": "shared", "directory_class": "mailbox", "handset_profile": "shared-account", "device_handset_profile": "shared-account"}, False),
        ({"mode": "personal", "account_kind": "shared", "directory_class": "mailbox", "handset_profile": "shared-account"}, True),
        ({"mode": "personal", "account_kind": "shared", "directory_class": "mailbox", "handset_profile": "shared-account", "device_handset_profile": "shared-account", "company_owned": False}, True),
        ({"mode": "personal", "account_kind": "shared", "directory_class": "mailbox", "handset_profile": "shared-account", "device_handset_profile": "shared-account", "group_owned": False}, True),
        ({"mode": "personal", "account_kind": "shared", "directory_class": "mailbox", "handset_profile": "shared-account", "device_handset_profile": "shared-account", "device_owned": False}, True),
        ({"mode": "personal", "account_kind": "shared", "directory_class": "mailbox", "handset_profile": "shared-account", "device_handset_profile": "shared-account", "interactive": False}, True),
        ({"mode": "personal", "account_kind": "shared", "directory_class": "mailbox", "handset_profile": "shared-account", "device_handset_profile": "shared-account", "noninteractive": True}, True),
        ({"mode": "personal", "account_kind": "shared", "directory_class": "mailbox", "handset_profile": "shared-account", "device_handset_profile": "shared-account", "group_user_uuid": "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"}, True),
        ({"mode": "personal", "account_kind": "shared", "directory_class": "mailbox", "handset_profile": "shared-account", "device_handset_profile": "shared-account", "binding_kind": "group"}, True),
        ({"mode": "personal", "account_kind": "shared", "directory_class": "mailbox", "handset_profile": "shared-account", "device_handset_profile": "shared-account", "binding_kind": "policy"}, True),
        ({"mode": "personal", "account_kind": "shared", "directory_class": "mailbox", "handset_profile": "shared-account", "device_handset_profile": "shared-account", "binding_user_pk": 99}, True),
        ({"mode": "personal", "account_kind": "shared", "directory_class": "mailbox", "handset_profile": "shared-account", "device_handset_profile": "shared-account", "extra_active_binding": True}, True),
        ({"mode": "personal", "account_kind": "shared", "directory_class": "mailbox", "handset_profile": "unknown", "device_handset_profile": "unknown"}, True),
        ({"mode": "personal", "account_kind": "person", "handset_profile": "shared-account", "device_handset_profile": "shared-account"}, True),
        ({"mode": "personal", "account_kind": "test", "directory_class": "system"}, True),
        ({"mode": "personal", "account_kind": "person", "directory_class": "system"}, True),
        ({"mode": "shared", "account_kind": "person"}, False),
        ({"mode": "shared", "account_kind": "shared", "directory_class": "mailbox"}, False),
        ({"mode": "shared", "account_kind": "shared", "directory_class": "mailbox", "handset_profile": "shared-account", "device_handset_profile": "shared-account"}, True),
        ({"mode": "shared", "account_kind": "test", "directory_class": "system"}, True),
        ({"mode": "shared", "account_kind": "service", "directory_class": "system"}, True),
        ({"mode": "shared", "account_kind": "person", "active": False}, True),
        ({"mode": "shared", "account_kind": "person", "user_type": "service_account"}, True),
        ({"mode": "shared", "account_kind": "person", "device_policy_passes": False}, True),
    ],
)
def test_endpoint_policy_keeps_shared_access_bound_to_device_policy(
    policy_name, request_kwargs, denied
):
    assert _policy(policy_name)(_request(**request_kwargs)) is denied


def test_shared_handset_change_does_not_deny_normal_browser_authentication():
    request = _request(
        mode="personal",
        account_kind="shared",
        directory_class="mailbox",
        handset_profile="shared-account",
        device_handset_profile="shared-account",
    )
    request.http_request.META["HTTP_USER_AGENT"] = "Mozilla/5.0 Firefox"

    assert _policy("DENY_DEVICE_ACCESS_EXPRESSION")(request) is False
    assert _policy("DENY_OIDC_DEVICE_ACCESS_EXPRESSION")(request) is True


def test_shared_handset_never_uses_personal_totp_only_reauthentication():
    request = _request(
        mode="personal",
        account_kind="shared",
        directory_class="mailbox",
        handset_profile="shared-account",
        device_handset_profile="shared-account",
    )
    request.context["flow_plan"].context.update({
        "application": SimpleNamespace(slug="mission-leben-portal"),
        "goauthentik.io/providers/oauth2/params": SimpleNamespace(prompt={"login"}),
        "pending_user_identifier": "house-account",
    })
    assert _policy("PERSONAL_REAUTHENTICATION_TOTP_EXPRESSION")(request) is False

    personal = _request(mode="personal", account_kind="person")
    personal.context["flow_plan"].context.update({
        "application": SimpleNamespace(slug="mission-leben-portal"),
        "goauthentik.io/providers/oauth2/params": SimpleNamespace(prompt={"login"}),
        "pending_user_identifier": "person-account",
    })
    assert _policy("PERSONAL_REAUTHENTICATION_TOTP_EXPRESSION")(personal) is True


def test_oidc_policy_denies_wrong_app_mode_for_shared_handset():
    request = _request(
        mode="personal",
        account_kind="shared",
        directory_class="mailbox",
        handset_profile="shared-account",
        device_handset_profile="shared-account",
    )
    request.http_request.META["HTTP_USER_AGENT"] = (
        "Android MissionLebenPortal/1.0 MissionLebenMode/shared"
    )
    assert _policy("DENY_OIDC_DEVICE_ACCESS_EXPRESSION")(request) is True
