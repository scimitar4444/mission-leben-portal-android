"""Bootstrap the Mission Leben device-initialization portal in authentik 2026.8.

Run this script inside the authentik server container with ``ak shell`` after a
database backup. Redirect stdout to the root-only API-token secret file. The
script creates no device data; it creates the proxy application, four operator
groups and a least-privilege service account used by the stateless portal.
"""

import os

from django.contrib.auth.models import Permission

from authentik.core.models import Application, Group, Token, TokenIntents, User, UserTypes
from authentik.flows.models import (
    Flow,
    FlowAuthenticationRequirement,
    FlowDesignation,
    FlowStageBinding,
    NotConfiguredAction,
)
from authentik.outposts.apps import MANAGED_OUTPOST
from authentik.outposts.models import Outpost
from authentik.policies.models import PolicyBinding, PolicyEngineMode
from authentik.providers.proxy.models import ProxyMode, ProxyProvider
from authentik.stages.authenticator_duo.models import AuthenticatorDuoStage
from authentik.stages.authenticator_validate.models import (
    AuthenticatorValidateStage,
    DeviceClasses,
)
from authentik.stages.identification.models import IdentificationStage, UserFields
from authentik.stages.password.models import PasswordStage
from authentik.stages.user_login.models import UserLoginStage


PROVIDER_NAME = "Mission Leben Geräte-Einrichtung"
APPLICATION_NAME = "Gerät einrichten"
APPLICATION_SLUG = "mission-leben-device-init"
SERVICE_USERNAME = "svc-mission-leben-device-enrollment"
SERVICE_TOKEN_IDENTIFIER = "mission-leben-device-enrollment-api"
DEFAULT_EXTERNAL_HOST = "https://geraete.mission-leben.de"
DEFAULT_AUTHENTIK_BROWSER_ORIGIN = "https://id.mission-leben.de"
AUTHENTICATION_FLOW_SLUG = "mission-leben-device-init-authentication"
AUTHORIZATION_FLOW_SLUG = "mission-leben-device-init-authorization"
IDENTIFICATION_STAGE_NAME = "Mission Leben Geräte-Einrichtung - Benutzer"
LOGIN_STAGE_NAME = "Mission Leben Geräte-Einrichtung - Browsersitzung"
PASSWORD_STAGE_NAME = "default-authentication-password"
AUTHORIZATION_MFA_STAGE_NAME = "Mission Leben Geräte-Einrichtung - Starke Anmeldung"
APP_APPROVAL_STAGE_NAME = "Mission Leben Zentral - App-Bestätigung"
ROLE_GROUPS = {
    "ML_DEVICE_INIT_IT": "it",
    "ML_DEVICE_INIT_ZENTRALE": "central",
    "ML_DEVICE_INIT_EL": "el",
    "ML_DEVICE_INIT_PDL": "pdl",
}
PERMISSIONS = (
    "authentik_core.view_user",
    "authentik_core.view_group",
    "authentik_endpoints.view_device",
    "authentik_endpoints.change_device",
    "authentik_endpoints.view_deviceaccessgroup",
    "authentik_endpoints.add_deviceaccessgroup",
    "authentik_endpoints.change_deviceaccessgroup",
    "authentik_endpoints.view_deviceuserbinding",
    "authentik_endpoints.add_deviceuserbinding",
    "authentik_endpoints_connectors_agent.view_enrollmenttoken",
    "authentik_endpoints_connectors_agent.add_enrollmenttoken",
    "authentik_endpoints_connectors_agent.change_enrollmenttoken",
    "authentik_endpoints_connectors_agent.delete_enrollmenttoken",
    "authentik_endpoints_connectors_agent.view_enrollment_token_key",
    "authentik_events.add_event",
    "authentik_stages_authenticator_duo.view_authenticatorduostage",
    "authentik_stages_authenticator_duo.add_duodevice",
)


external_host = os.environ.get("ML_ENROLL_PUBLIC_ORIGIN", DEFAULT_EXTERNAL_HOST).strip().rstrip("/")
authentik_browser_origin = os.environ.get(
    "ML_ENROLL_AUTHENTIK_BROWSER_ORIGIN", DEFAULT_AUTHENTIK_BROWSER_ORIGIN
).strip().rstrip("/")
authentication_flow, _ = Flow.objects.update_or_create(
    slug=AUTHENTICATION_FLOW_SLUG,
    defaults={
        "name": "Mission Leben Geräte-Einrichtung anmelden",
        "title": "Geräte-Einrichtung anmelden",
        "designation": FlowDesignation.AUTHENTICATION,
        "authentication": FlowAuthenticationRequirement.NONE,
        "policy_engine_mode": PolicyEngineMode.MODE_ANY,
    },
)
identification_stage, _ = IdentificationStage.objects.update_or_create(
    name=IDENTIFICATION_STAGE_NAME,
    defaults={
        "user_fields": [UserFields.USERNAME],
        "case_insensitive_matching": True,
        "show_matched_user": False,
        "pretend_user_exists": True,
        "enable_remember_me": False,
        "password_stage": None,
        "captcha_stage": None,
        "webauthn_stage": None,
    },
)
password_stage = PasswordStage.objects.get(name=PASSWORD_STAGE_NAME)
login_stage, _ = UserLoginStage.objects.update_or_create(
    name=LOGIN_STAGE_NAME,
    defaults={
        "session_duration": "minutes=10",
        "remember_me_offset": "seconds=0",
        "remember_device": "seconds=0",
        "terminate_other_sessions": False,
    },
)
authorization_mfa, _ = AuthenticatorValidateStage.objects.update_or_create(
    name=AUTHORIZATION_MFA_STAGE_NAME,
    defaults={
        "not_configured_action": NotConfiguredAction.DENY,
        # App approval is an additional option for users who already have a
        # personal device. TOTP and WebAuthn remain available for first-device
        # enrollment and recovery. The Android app's own authentication flow
        # deliberately keeps Duo disabled to avoid a circular login.
        "device_classes": [
            DeviceClasses.TOTP,
            DeviceClasses.WEBAUTHN,
            DeviceClasses.DUO,
        ],
        # The same stage is present in both flows. A fresh login validates an
        # enrolled TOTP authenticator or passkey in the authentication flow;
        # the short-lived stage cookie prevents a duplicate prompt in
        # authorization. An older authentik session has no such cookie and is
        # therefore challenged by authorization.
        "last_auth_threshold": "minutes=2",
    },
)
authorization_mfa.configuration_stages.clear()
authentication_bindings = []
for order, stage in (
    (10, identification_stage),
    (20, password_stage),
    (30, authorization_mfa),
    (100, login_stage),
):
    binding, _ = FlowStageBinding.objects.update_or_create(
        target=authentication_flow,
        stage=stage,
        defaults={
            "order": order,
            "evaluate_on_plan": False,
            "re_evaluate_policies": True,
        },
    )
    if binding.policies.exists():
        raise RuntimeError(
            "Device-initialization authentication bindings must not have policies"
        )
    authentication_bindings.append(binding)
unexpected_authentication_bindings = FlowStageBinding.objects.filter(
    target=authentication_flow
).exclude(pk__in=[binding.pk for binding in authentication_bindings])
if unexpected_authentication_bindings.exists():
    raise RuntimeError(
        "Device-initialization authentication flow has unexpected stage bindings; "
        "review them manually"
    )

# A pre-existing authentik browser session may have been created with only a
# password. This provider-specific authorization flow therefore always demands
# an already configured TOTP authenticator or passkey before the portal is entered.
authorization_flow, _ = Flow.objects.update_or_create(
    slug=AUTHORIZATION_FLOW_SLUG,
    defaults={
        "name": "Mission Leben Geräte-Einrichtung autorisieren",
        "title": "Geräte-Einrichtung bestätigen",
        "designation": FlowDesignation.AUTHORIZATION,
        "authentication": FlowAuthenticationRequirement.REQUIRE_AUTHENTICATED,
        "policy_engine_mode": PolicyEngineMode.MODE_ANY,
    },
)
authorization_binding, _ = FlowStageBinding.objects.update_or_create(
    target=authorization_flow,
    stage=authorization_mfa,
    defaults={
        "order": 10,
        "evaluate_on_plan": False,
        "re_evaluate_policies": True,
    },
)
if authorization_binding.policies.exists():
    raise RuntimeError(
        "Device-initialization authorization binding must run unconditionally"
    )
unexpected_authorization_bindings = FlowStageBinding.objects.filter(
    target=authorization_flow
).exclude(pk=authorization_binding.pk)
if unexpected_authorization_bindings.exists():
    raise RuntimeError(
        "Device-initialization authorization flow has unexpected stage bindings; review them manually"
    )

provider, _ = ProxyProvider.objects.update_or_create(
    name=PROVIDER_NAME,
    defaults={
        "authentication_flow": authentication_flow,
        "authorization_flow": authorization_flow,
        "external_host": external_host,
        "internal_host": "",
        "internal_host_ssl_validation": True,
        "mode": ProxyMode.FORWARD_SINGLE,
        "skip_path_regex": (
            r"^/healthz$" + "\n" + r"^/api/v1/enrollments/[0-9a-fA-F-]{36}/redeem$"
        ),
        "intercept_header_auth": False,
    },
)
provider.set_oauth_defaults()
provider.save()

application, _ = Application.objects.update_or_create(
    slug=APPLICATION_SLUG,
    defaults={
        "name": APPLICATION_NAME,
        "provider": provider,
        "meta_launch_url": external_host + "/self",
        "meta_description": "Eigenes Gerät per TOTP oder Passkey registrieren",
        "meta_publisher": "Mission Leben",
        "meta_hide": False,
        "policy_engine_mode": PolicyEngineMode.MODE_ANY,
    },
)

operator_groups = []
for name, role in ROLE_GROUPS.items():
    group, _ = Group.objects.get_or_create(name=name)
    group.is_superuser = False
    group.attributes = {
        **group.attributes,
        "mission-leben.de/purpose": "device-initialization",
        "mission-leben.de/device-initializer-role": role,
    }
    group.save(update_fields=["is_superuser", "attributes"])
    operator_groups.append(group)

# Every active user with an existing TOTP authenticator or passkey may enter
# the self-service page. The portal itself still checks the four operator roles
# before exposing employee search or shared-device initialization. Remove only
# bindings managed by the previous role-gated version and fail closed on every
# unknown binding.
unexpected_bindings = PolicyBinding.objects.filter(target=application).exclude(
    group__in=operator_groups
)
if unexpected_bindings.exists():
    raise RuntimeError(
        "Gerät einrichten has unexpected policy/user/group bindings; review them manually"
    )
PolicyBinding.objects.filter(target=application, group__in=operator_groups).delete()

embedded_outpost = Outpost.objects.get(managed=MANAGED_OUTPOST)
outpost_config = embedded_outpost.config
configured_browser_origin = outpost_config.authentik_host_browser.rstrip("/")
if configured_browser_origin and configured_browser_origin != authentik_browser_origin:
    raise RuntimeError(
        "Embedded outpost already uses a different authentik_host_browser; "
        "review it manually before changing the device portal"
    )
configured_authentik_origin = outpost_config.authentik_host.rstrip("/")
if (
    configured_authentik_origin != authentik_browser_origin
    and embedded_outpost.providers.exclude(pk=provider.pk).exists()
):
    raise RuntimeError(
        "Embedded outpost already serves other providers and uses a different "
        "authentik_host; review it manually before changing the device portal"
    )
outpost_config_changed = False
if configured_authentik_origin != authentik_browser_origin:
    # The embedded outpost uses its public authentik_host for browser redirects.
    # Its internal API connection continues through authentik's local IPC path.
    outpost_config.authentik_host = authentik_browser_origin
    outpost_config_changed = True
if not configured_browser_origin:
    outpost_config.authentik_host_browser = authentik_browser_origin
    outpost_config_changed = True
if outpost_config_changed:
    embedded_outpost.config = outpost_config
    embedded_outpost.save(update_fields=["_config"])
embedded_outpost.providers.add(provider)

service_user, _ = User.objects.update_or_create(
    username=SERVICE_USERNAME,
    defaults={
        "name": "Mission Leben Geräte-Einrichtung API",
        "email": "",
        "is_active": True,
        "type": UserTypes.SERVICE_ACCOUNT,
        "path": "goauthentik.io/service-accounts/mission-leben",
    },
)

# Resolve every permission before changing the managed role, so a version drift
# cannot silently leave a half-privileged service account behind.
resolved_permissions = []
for permission_name in PERMISSIONS:
    app_label, codename = permission_name.split(".", 1)
    resolved_permissions.append(
        Permission.objects.get(content_type__app_label=app_label, codename=codename)
    )
service_user.remove_all_perms_from_managed_role()
service_user.assign_perms_to_managed_role(resolved_permissions)

# Authentik 2026.8.3 additionally applies the generic POST object check when
# import_device_manual resolves its Duo stage. Keep that otherwise broad
# add-stage permission object-scoped to the one existing app-approval stage.
app_approval_stage = AuthenticatorDuoStage.objects.get(name=APP_APPROVAL_STAGE_NAME)
service_user.assign_perms_to_managed_role(
    "authentik_stages_authenticator_duo.add_authenticatorduostage",
    app_approval_stage,
)

api_token, _ = Token.objects.update_or_create(
    identifier=SERVICE_TOKEN_IDENTIFIER,
    defaults={
        "user": service_user,
        "intent": TokenIntents.INTENT_API,
        "description": "Least-privilege API token for the device-initialization container",
        "expiring": False,
        "expires": None,
    },
)

# This is the only secret output. Invoke with stdout redirected to a root-only
# file and mount that file read-only into the container.
print(api_token.key)
