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
from authentik.stages.authenticator_validate.models import (
    AuthenticatorValidateStage,
    DeviceClasses,
)


PROVIDER_NAME = "Mission Leben Geräte-Einrichtung"
APPLICATION_NAME = "Gerät einrichten"
APPLICATION_SLUG = "mission-leben-device-init"
SERVICE_USERNAME = "svc-mission-leben-device-enrollment"
SERVICE_TOKEN_IDENTIFIER = "mission-leben-device-enrollment-api"
DEFAULT_EXTERNAL_HOST = "https://geraete.mission-leben.de"
AUTHORIZATION_FLOW_SLUG = "mission-leben-device-init-authorization"
AUTHORIZATION_MFA_STAGE_NAME = "Mission Leben Geräte-Einrichtung - Starke Anmeldung"
ROLE_GROUPS = {
    "ML_DEVICE_INIT_IT": "it",
    "ML_DEVICE_INIT_ZENTRALE": "central",
    "ML_DEVICE_INIT_EL": "el",
    "ML_DEVICE_INIT_PDL": "pdl",
}
PERMISSIONS = (
    "authentik_core.view_user",
    "authentik_core.view_group",
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
)


external_host = os.environ.get("ML_ENROLL_PUBLIC_ORIGIN", DEFAULT_EXTERNAL_HOST).strip().rstrip("/")
authentication_flow = Flow.objects.get(
    slug=os.environ.get("ML_ENROLL_AUTHENTICATION_FLOW_SLUG", "mission-leben-browser-authentication")
)

# A pre-existing authentik browser session may have been created with only a
# password. This provider-specific authorization flow therefore always demands
# an already configured TOTP or WebAuthn factor before the portal is entered.
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
authorization_mfa, _ = AuthenticatorValidateStage.objects.update_or_create(
    name=AUTHORIZATION_MFA_STAGE_NAME,
    defaults={
        "not_configured_action": NotConfiguredAction.DENY,
        "device_classes": [DeviceClasses.TOTP, DeviceClasses.WEBAUTHN],
        "last_auth_threshold": "seconds=0",
    },
)
authorization_mfa.configuration_stages.clear()
authorization_binding, _ = FlowStageBinding.objects.update_or_create(
    target=authorization_flow,
    stage=authorization_mfa,
    defaults={
        "order": 10,
        "evaluate_on_plan": False,
        "re_evaluate_policies": True,
    },
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
        "meta_launch_url": external_host + "/",
        "meta_description": "Persönliche Geräte und Shared Tablets sicher initialisieren",
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
    PolicyBinding.objects.update_or_create(
        target=application,
        group=group,
        defaults={"order": 0, "enabled": True, "negate": False},
    )

# This application is role-gated. Never silently delete an administrator's
# additional policy; fail closed if the existing target has any other binding.
unexpected_bindings = PolicyBinding.objects.filter(target=application).exclude(
    group__in=operator_groups
)
if unexpected_bindings.exists():
    raise RuntimeError(
        "Gerät einrichten has unexpected policy/user/group bindings; review them manually"
    )

embedded_outpost = Outpost.objects.get(managed=MANAGED_OUTPOST)
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
