"""Idempotent Authentik 2026.8 bootstrap for Mission Leben Zentral Android.

Run inside the Authentik server container with ``ak shell`` after a PostgreSQL backup.
No enrollment token or secret is printed or stored by this script.
"""

import json
import os

from authentik.common.oauth.constants import SubModes
from authentik.core.models import Application, Group, User
from authentik.crypto.builder import CertificateBuilder, PrivateKeyAlg
from authentik.crypto.models import CertificateKeyPair
from authentik.endpoints.connectors.agent.models import AgentConnector
from authentik.endpoints.models import (
    DeviceAccessGroup,
    DeviceUserBinding,
    EndpointStage,
    StageMode,
)
from authentik.flows.models import (
    Flow,
    FlowAuthenticationRequirement,
    FlowDesignation,
    FlowStageBinding,
    NotConfiguredAction,
)
from authentik.policies.expression.models import ExpressionPolicy
from authentik.policies.models import PolicyBinding, PolicyEngineMode
from authentik.providers.oauth2.models import (
    ClientType,
    GrantType,
    OAuth2Provider,
    RedirectURIMatchingMode,
    RedirectURIType,
    ScopeMapping,
)
from authentik.stages.authenticator_validate.models import (
    AuthenticatorValidateStage,
    DeviceClasses,
)
from authentik.stages.deny.models import DenyStage
from authentik.stages.identification.models import IdentificationStage, UserFields
from authentik.stages.password.models import PasswordStage
from authentik.stages.user_login.models import UserLoginStage


CONNECTOR_NAME = "Mission Leben Android"
LEGACY_ACCESS_GROUP_NAME = "Mission Leben Android - Pilot"
CERTIFICATE_NAME = "Mission Leben Android Endpoint Challenge"
BASE_AUTHENTICATION_FLOW_SLUG = "mission-leben-browser-authentication"
ANDROID_AUTHENTICATION_FLOW_SLUG = "mission-leben-android-authentication"
ANDROID_AUTHORIZATION_FLOW_SLUG = "mission-leben-android-authorization"
PERSONAL_SESSION_FLOW_SLUGS = (
    ANDROID_AUTHENTICATION_FLOW_SLUG,
    "mission-leben-zimbra-authentication",
)
INVALIDATION_FLOW_SLUG = "default-provider-invalidation-flow"
LEGACY_PILOT_GROUP_NAME = "authentik Admins"
PROVIDER_NAME = "Provider for Mission Leben Zentral Android"
APPLICATION_NAME = "Mission Leben Zentral Android"
APPLICATION_SLUG = "mission-leben-portal"
CLIENT_ID = "mission-leben-android"
REDIRECT_URIS = (
    "de.missionleben.portal:/oauth2redirect",
    "de.missionleben.portal.debug:/oauth2redirect",
)
PASSWORD_STAGE_NAME = "default-authentication-password"
ANDROID_IDENTIFICATION_STAGE_NAME = "Mission Leben Zentral Android - Benutzer"
ANDROID_TOTP_STAGE_NAME = "Mission Leben Zentral Android - Vorhandenes TOTP bei Wiederanmeldung"
LEGACY_ANDROID_TOTP_STAGE_NAME = "Mission Leben Zentral Android - TOTP alle 90 Tage"
ANDROID_SHARED_SESSION_STAGE_NAME = "Mission Leben Zentral Android - Shared Browsersitzung"
PERSONAL_SESSION_STAGE_NAME = "Mission Leben Zentral Android - Persönliche Browsersitzung"
PERSONAL_SESSION_POLICY_NAME = "Mission Leben Zentral Android - Persönlicher WebView"
PERSONAL_SESSION_DURATION = "days=90"
REFRESH_TOKEN_VALIDITY = "days=90"
# authentik otherwise renews every rotating refresh token for another full
# validity period. One second makes the 90-day lifetime effectively absolute.
REFRESH_TOKEN_RENEWAL_THRESHOLD = "seconds=1"
REAUTHENTICATION_TOTP_POLICY_NAME = (
    "Mission Leben Zentral Android - Vorhandenes TOTP bei Wiederanmeldung"
)
LEGACY_REAUTHENTICATION_POLICY_NAME = (
    "Mission Leben Zentral Android - Passwort nach Geräteprüfung überspringen"
)
MOBILE_APPLICATION_GROUP = "Mobil erreichbar"
MOBILE_APPLICATION_SLUGS = (
    "zimbra-mail",
    "exchange-owa",
    "talk",
)
TALK_DIRECT_LAUNCH_URL = (
    "https://nextcloud.mission-leben.de/apps/user_oidc/login/4"
    "?redirectUrl=https%3A%2F%2Fnextcloud.mission-leben.de%2Fapps%2Fspreed%2F"
)
TALK_HANDOFF_GROUP_NAME = "ENT_TALK_RAUMUEBERGABE"
DEVICE_PROFILE_SWITCH_GROUP_NAME = "ENT_DEVICE_PROFILE_SWITCH"
DEVICE_PROFILE_SWITCH_PILOT_USERNAME = os.environ.get(
    "ML_DEVICE_PROFILE_SWITCH_PILOT_USERNAME", ""
).strip()
FEATURE_SCOPE_NAME = "ml_features"
FEATURE_SCOPE_MAPPING_NAME = "Mission Leben Zentral Android - Funktionsberechtigungen"


FEATURE_SCOPE_EXPRESSION = f'''group_names = {{group.name for group in request.user.all_groups()}}
capabilities = []
if "{TALK_HANDOFF_GROUP_NAME}" in group_names:
    capabilities.append("open_talk")
if "{DEVICE_PROFILE_SWITCH_GROUP_NAME}" in group_names:
    capabilities.append("device_profile_switch")
return {{"ml_capabilities": capabilities}}
'''


PORTAL_REQUEST_EXPRESSION = r'''http_request = request.http_request
if not http_request:
    return False
user_agent = http_request.META.get("HTTP_USER_AGENT", "")
return "Android" in user_agent and "MissionLebenPortal/" in user_agent
'''


DENY_DEVICE_ACCESS_EXPRESSION = r'''http_request = request.http_request
if not http_request:
    return False
user_agent = http_request.META.get("HTTP_USER_AGENT", "")
if "Android" not in user_agent or "MissionLebenPortal/" not in user_agent:
    return False

flow_plan = request.context.get("flow_plan")
device = request.context.get("device")
if device is None and flow_plan:
    device = flow_plan.context.get("device")
pending_user = flow_plan.context.get("pending_user") if flow_plan else None
if (
    device is None
    or pending_user is None
    or device.is_expired
    or device.attributes.get("mission-leben.de/status") == "disabled"
):
    return True

access_group = device.access_group
approved_mode = access_group.attributes.get("mission-leben.de/mode") if access_group else None
reported_mode = (
    device.facts.data.get("vendor", {})
    .get("mission-leben.de/portal", {})
    .get("mode")
)
if approved_mode not in ("personal", "shared") or reported_mode != approved_mode:
    return True

from authentik.endpoints.connectors.agent.auth import check_device_policies
if not check_device_policies(device, pending_user, http_request).passing:
    return True

attributes = pending_user.attributes or {}
if not pending_user.is_active or pending_user.type == "service_account":
    return True
group_attributes = access_group.attributes or {}
device_attributes = device.attributes or {}
handset_profile = group_attributes.get("mission-leben.de/handset-profile")
device_handset_profile = device_attributes.get("mission-leben.de/handset-profile")
if approved_mode == "shared":
    if handset_profile is not None or device_handset_profile is not None:
        return True
    return not (
        attributes.get("iam_account_kind") == "shared"
        or (
            attributes.get("iam_account_kind") == "person"
            and attributes.get("iam_directory_class") == "person"
        )
    )
if handset_profile == "shared-account":
    handset_allowed = (
        device_handset_profile == handset_profile
        and group_attributes.get("mission-leben.de/device-ownership") == "company"
        and device_attributes.get("mission-leben.de/device-ownership") == "company"
        and str(group_attributes.get("mission-leben.de/user-uuid")) == str(pending_user.uuid)
        and attributes.get("iam_account_kind") == "shared"
        and attributes.get("iam_directory_class") == "mailbox"
        and attributes.get("iam_interactive_login_allowed") is True
        and attributes.get("iam_noninteractive_account") is False
    )
    if not handset_allowed:
        return True
    from authentik.endpoints.models import DeviceUserBinding
    bindings = list(DeviceUserBinding.objects.filter(target=access_group, enabled=True))
    return not (
        len(bindings) == 1
        and not bindings[0].negate
        and bindings[0].user_id == pending_user.pk
        and bindings[0].group_id is None
        and bindings[0].policy_id is None
    )
if handset_profile is not None or device_handset_profile is not None:
    return True
return not (
    attributes.get("iam_account_kind") == "person"
    and attributes.get("iam_directory_class") == "person"
)
'''


# The OAuth provider's authorization flow runs even if authentik already has a
# browser session. It must therefore prove the endpoint again instead of
# trusting that session. Unlike the authentication-flow policy, this one is
# intentionally fail-closed for every non-app User-Agent and can fall back to
# the already authenticated request user.
DENY_OIDC_DEVICE_ACCESS_EXPRESSION = r'''http_request = request.http_request
if not http_request:
    return True
user_agent = http_request.META.get("HTTP_USER_AGENT", "")
if "Android" not in user_agent or "MissionLebenPortal/" not in user_agent:
    return True

flow_plan = request.context.get("flow_plan")
device = request.context.get("device")
if device is None and flow_plan:
    device = flow_plan.context.get("device")
pending_user = flow_plan.context.get("pending_user") if flow_plan else None
if pending_user is None:
    pending_user = http_request.user
if (
    device is None
    or pending_user is None
    or getattr(pending_user, "is_anonymous", True)
    or device.is_expired
    or device.attributes.get("mission-leben.de/status") == "disabled"
):
    return True

access_group = device.access_group
approved_mode = access_group.attributes.get("mission-leben.de/mode") if access_group else None
reported_mode = (
    device.facts.data.get("vendor", {})
    .get("mission-leben.de/portal", {})
    .get("mode")
)
if approved_mode not in ("personal", "shared") or reported_mode != approved_mode:
    return True
if f"MissionLebenMode/{approved_mode}" not in user_agent:
    return True

from authentik.endpoints.connectors.agent.auth import check_device_policies
if not check_device_policies(device, pending_user, http_request).passing:
    return True

attributes = pending_user.attributes or {}
if not pending_user.is_active or pending_user.type == "service_account":
    return True
group_attributes = access_group.attributes or {}
device_attributes = device.attributes or {}
handset_profile = group_attributes.get("mission-leben.de/handset-profile")
device_handset_profile = device_attributes.get("mission-leben.de/handset-profile")
if approved_mode == "shared":
    if handset_profile is not None or device_handset_profile is not None:
        return True
    return not (
        attributes.get("iam_account_kind") == "shared"
        or (
            attributes.get("iam_account_kind") == "person"
            and attributes.get("iam_directory_class") == "person"
        )
    )
if handset_profile == "shared-account":
    handset_allowed = (
        device_handset_profile == handset_profile
        and group_attributes.get("mission-leben.de/device-ownership") == "company"
        and device_attributes.get("mission-leben.de/device-ownership") == "company"
        and str(group_attributes.get("mission-leben.de/user-uuid")) == str(pending_user.uuid)
        and attributes.get("iam_account_kind") == "shared"
        and attributes.get("iam_directory_class") == "mailbox"
        and attributes.get("iam_interactive_login_allowed") is True
        and attributes.get("iam_noninteractive_account") is False
    )
    if not handset_allowed:
        return True
    from authentik.endpoints.models import DeviceUserBinding
    bindings = list(DeviceUserBinding.objects.filter(target=access_group, enabled=True))
    return not (
        len(bindings) == 1
        and not bindings[0].negate
        and bindings[0].user_id == pending_user.pk
        and bindings[0].group_id is None
        and bindings[0].policy_id is None
    )
if handset_profile is not None or device_handset_profile is not None:
    return True
return not (
    attributes.get("iam_account_kind") == "person"
    and attributes.get("iam_directory_class") == "person"
)
'''


PERSONAL_REAUTHENTICATION_TOTP_EXPRESSION = r'''http_request = request.http_request
if not http_request:
    return False
user_agent = http_request.META.get("HTTP_USER_AGENT", "")
if not (
    "Android" in user_agent
    and "MissionLebenPortal/" in user_agent
    and "MissionLebenMode/personal" in user_agent
):
    return False

flow_plan = request.context.get("flow_plan")
if not flow_plan:
    return False
application = flow_plan.context.get("application")
if application is None or application.slug != "mission-leben-portal":
    return False
params = flow_plan.context.get("goauthentik.io/providers/oauth2/params")
prompts = getattr(params, "prompt", set()) if params else set()
if "login" not in prompts or not flow_plan.context.get("pending_user_identifier"):
    return False

device = request.context.get("device") or flow_plan.context.get("device")
pending_user = flow_plan.context.get("pending_user")
if (
    device is None
    or pending_user is None
    or device.is_expired
    or device.attributes.get("mission-leben.de/status") == "disabled"
):
    return False

access_group = device.access_group
approved_mode = access_group.attributes.get("mission-leben.de/mode") if access_group else None
reported_mode = (
    device.facts.data.get("vendor", {})
    .get("mission-leben.de/portal", {})
    .get("mode")
)
if approved_mode != "personal" or reported_mode != "personal":
    return False
if (access_group.attributes or {}).get("mission-leben.de/handset-profile") is not None:
    return False
attributes = pending_user.attributes or {}
if not (
    pending_user.is_active
    and attributes.get("iam_account_kind") == "person"
    and attributes.get("iam_directory_class") == "person"
):
    return False

from authentik.endpoints.connectors.agent.auth import check_device_policies
if not check_device_policies(device, pending_user, http_request).passing:
    return False

from authentik.stages.authenticator_totp.models import TOTPDevice
return TOTPDevice.objects.filter(user=pending_user, confirmed=True).exists()
'''


PERSONAL_WEBVIEW_EXPRESSION = r'''http_request = request.http_request
if not http_request:
    return False
user_agent = http_request.META.get("HTTP_USER_AGENT", "")
return (
    "Android" in user_agent
    and "MissionLebenPortal/" in user_agent
    and "MissionLebenMode/personal" in user_agent
)
'''


challenge_key = CertificateKeyPair.objects.filter(name=CERTIFICATE_NAME).first()
if challenge_key is None:
    builder = CertificateBuilder(CERTIFICATE_NAME)
    builder.alg = PrivateKeyAlg.ECDSA
    builder.build(validity_days=3650)
    challenge_key = builder.save()

connector, _ = AgentConnector.objects.update_or_create(
    name=CONNECTOR_NAME,
    defaults={
        "enabled": True,
        "snapshot_expiry": "hours=24",
        "refresh_interval": "minutes=30",
        "challenge_key": challenge_key,
        "challenge_idle_timeout": "seconds=10",
        "challenge_trigger_check_in": False,
    },
)

# The former pilot group allowed every pilot user on every pilot device. Remove
# that broad binding; assign_device_access.py migrates devices to a user or a
# facility group explicitly.
legacy_access_group = DeviceAccessGroup.objects.filter(name=LEGACY_ACCESS_GROUP_NAME).first()
if legacy_access_group:
    DeviceUserBinding.objects.filter(target=legacy_access_group).delete()
    legacy_access_group.attributes = {
        **legacy_access_group.attributes,
        "mission-leben.de/status": "legacy-unassigned",
    }
    legacy_access_group.save(update_fields=["attributes"])

base_authentication_flow = Flow.objects.get(slug=BASE_AUTHENTICATION_FLOW_SLUG)
authentication_flow, _ = Flow.objects.update_or_create(
    slug=ANDROID_AUTHENTICATION_FLOW_SLUG,
    defaults={
        "name": "Mission Leben Zentral Android - Anmeldung",
        "title": "Mission Leben Zentral",
        "designation": FlowDesignation.AUTHENTICATION,
        "authentication": base_authentication_flow.authentication,
        "policy_engine_mode": base_authentication_flow.policy_engine_mode,
        "compatibility_mode": base_authentication_flow.compatibility_mode,
        "layout": base_authentication_flow.layout,
        "denied_action": base_authentication_flow.denied_action,
    },
)
authorization_flow, _ = Flow.objects.update_or_create(
    slug=ANDROID_AUTHORIZATION_FLOW_SLUG,
    defaults={
        "name": "Mission Leben Zentral Android - Autorisierung",
        "title": "Gerät bestätigen",
        "designation": FlowDesignation.AUTHORIZATION,
        "authentication": FlowAuthenticationRequirement.REQUIRE_AUTHENTICATED,
        "policy_engine_mode": PolicyEngineMode.MODE_ANY,
    },
)
invalidation_flow = Flow.objects.get(slug=INVALIDATION_FLOW_SLUG)
signing_key = CertificateKeyPair.objects.filter(name="authentik Self-signed Certificate").first()
if signing_key is None:
    signing_key = CertificateKeyPair.objects.filter(key_data__gt="").first()
if signing_key is None:
    raise RuntimeError("No Authentik signing key is available for the OIDC provider")

provider, _ = OAuth2Provider.objects.update_or_create(
    name=PROVIDER_NAME,
    defaults={
        "authentication_flow": authentication_flow,
        "authorization_flow": authorization_flow,
        "invalidation_flow": invalidation_flow,
        "client_type": ClientType.PUBLIC,
        "client_id": CLIENT_ID,
        "client_secret": "",
        "grant_types": [GrantType.AUTHORIZATION_CODE, GrantType.REFRESH_TOKEN],
        "_redirect_uris": [
            {
                "url": redirect_uri,
                "matching_mode": RedirectURIMatchingMode.STRICT,
                "redirect_uri_type": RedirectURIType.AUTHORIZATION,
            }
            for redirect_uri in REDIRECT_URIS
        ],
        "access_code_validity": "minutes=1",
        "access_token_validity": "minutes=5",
        "refresh_token_validity": REFRESH_TOKEN_VALIDITY,
        "refresh_token_threshold": REFRESH_TOKEN_RENEWAL_THRESHOLD,
        # The bridge stores this stable subject after OIDC login. App-approval
        # DuoDevice records use the same Authentik user.uid value.
        "sub_mode": SubModes.HASHED_USER_ID,
        "signing_key": signing_key,
    },
)
talk_handoff_group, _ = Group.objects.get_or_create(name=TALK_HANDOFF_GROUP_NAME)
profile_switch_group, _ = Group.objects.get_or_create(name=DEVICE_PROFILE_SWITCH_GROUP_NAME)
profile_switch_pilot = None
if DEVICE_PROFILE_SWITCH_PILOT_USERNAME:
    profile_switch_pilot = User.objects.filter(
        username=DEVICE_PROFILE_SWITCH_PILOT_USERNAME
    ).first()
    if profile_switch_pilot is None:
        raise RuntimeError("Configured profile-switch pilot user does not exist")
    unexpected_profile_switch_users = profile_switch_group.users.exclude(
        pk=profile_switch_pilot.pk
    )
    if unexpected_profile_switch_users.exists():
        raise RuntimeError(
            "ENT_DEVICE_PROFILE_SWITCH contains unexpected pilot members; review them manually"
        )
    profile_switch_group.users.add(profile_switch_pilot)

feature_scope, _ = ScopeMapping.objects.update_or_create(
    name=FEATURE_SCOPE_MAPPING_NAME,
    defaults={
        "scope_name": FEATURE_SCOPE_NAME,
        "description": "Kleine Capability-Liste für Mission Leben Zentral Android",
        "expression": FEATURE_SCOPE_EXPRESSION,
    },
)
default_scope_mappings = list(
    ScopeMapping.objects.filter(
        scope_name__in=["openid", "profile", "email", "offline_access", "goauthentik.io/api"],
        name__startswith="authentik default OAuth Mapping:",
    )
)
provider.property_mappings.set([*default_scope_mappings, feature_scope])

application, _ = Application.objects.update_or_create(
    slug=APPLICATION_SLUG,
    defaults={
        "name": APPLICATION_NAME,
        "provider": provider,
        "meta_description": "Sicherer Android-Zugang zu den freigegebenen Mission-Leben-Anwendungen",
        "meta_publisher": "Mission Leben",
        "meta_hide": True,
        "policy_engine_mode": PolicyEngineMode.MODE_ANY,
    },
)

# The existing Authentik policies remain authoritative for user access. This
# additional application group is a central mobile-availability marker; the
# Android client deliberately hides every otherwise permitted application that
# is not marked. Warden remains available in the normal portal but is not marked
# until its Nextcloud route is externally reachable.
mobile_applications = Application.objects.filter(slug__in=MOBILE_APPLICATION_SLUGS)
found_mobile_slugs = set(mobile_applications.values_list("slug", flat=True))
missing_mobile_slugs = set(MOBILE_APPLICATION_SLUGS) - found_mobile_slugs
if missing_mobile_slugs:
    raise RuntimeError(
        "Missing Authentik applications for mobile tagging: "
        + ", ".join(sorted(missing_mobile_slugs))
    )
mobile_applications.update(group=MOBILE_APPLICATION_GROUP)
# Enter Nextcloud through its existing central user_oidc provider. Opening the
# Talk root directly would first show Nextcloud's provider chooser and require
# an otherwise redundant tap on "Mission Leben". The redirect target remains
# the Talk root so the Android WebView can restore its locally remembered room.
Application.objects.filter(slug="talk").update(meta_launch_url=TALK_DIRECT_LAUNCH_URL)

# The Android client is no longer an administrator-only pilot. Authentication
# still fails closed for every unregistered or wrongly bound endpoint, and the
# applications shown after login keep their existing APP_* policies. Remove
# only the known former pilot binding and stop on any unknown application rule.
legacy_pilot_group = Group.objects.filter(name=LEGACY_PILOT_GROUP_NAME).first()
unexpected_application_bindings = PolicyBinding.objects.filter(target=application)
if legacy_pilot_group is not None:
    unexpected_application_bindings = unexpected_application_bindings.exclude(
        group=legacy_pilot_group
    )
if unexpected_application_bindings.exists():
    raise RuntimeError(
        "Mission Leben Zentral Android has unexpected application bindings; review them manually"
    )
if legacy_pilot_group is not None:
    PolicyBinding.objects.filter(target=application, group=legacy_pilot_group).delete()

portal_request_policy, _ = ExpressionPolicy.objects.update_or_create(
    name="Mission Leben Zentral Android - App-Anfrage",
    defaults={"expression": PORTAL_REQUEST_EXPRESSION},
)
deny_device_policy, _ = ExpressionPolicy.objects.update_or_create(
    name="Mission Leben Zentral Android - Gerätezugriff verweigern",
    defaults={"expression": DENY_DEVICE_ACCESS_EXPRESSION},
)
deny_oidc_device_policy, _ = ExpressionPolicy.objects.update_or_create(
    name="Mission Leben Zentral Android - OIDC-Gerätezugriff verweigern",
    defaults={"expression": DENY_OIDC_DEVICE_ACCESS_EXPRESSION},
)
reauthentication_totp_policy = ExpressionPolicy.objects.filter(
    name=REAUTHENTICATION_TOTP_POLICY_NAME
).first()
if reauthentication_totp_policy is None:
    reauthentication_totp_policy = ExpressionPolicy.objects.filter(
        name=LEGACY_REAUTHENTICATION_POLICY_NAME
    ).first()
if reauthentication_totp_policy is None:
    reauthentication_totp_policy = ExpressionPolicy(name=REAUTHENTICATION_TOTP_POLICY_NAME)
reauthentication_totp_policy.name = REAUTHENTICATION_TOTP_POLICY_NAME
reauthentication_totp_policy.expression = PERSONAL_REAUTHENTICATION_TOTP_EXPRESSION
reauthentication_totp_policy.save()
personal_webview_policy, _ = ExpressionPolicy.objects.update_or_create(
    name=PERSONAL_SESSION_POLICY_NAME,
    defaults={"expression": PERSONAL_WEBVIEW_EXPRESSION},
)

# The provider gets its own authentication flow. The existing central browser
# flow contains conditional internal/external identification and SPNEGO policies;
# modifying those bindings would risk unrelated applications. This simple,
# policy-free stage can consume OIDC login_hint without showing a username page.
identification_stage, _ = IdentificationStage.objects.update_or_create(
    name=ANDROID_IDENTIFICATION_STAGE_NAME,
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
identification_binding, _ = FlowStageBinding.objects.update_or_create(
    target=authentication_flow,
    stage=identification_stage,
    defaults={
        "order": 10,
        "evaluate_on_plan": False,
        "re_evaluate_policies": True,
    },
)
if identification_binding.policies.exists():
    raise RuntimeError(
        "The Android identification binding must not have policies; Authentik "
        "otherwise cannot consume OIDC login_hint automatically"
    )

endpoint_stage, _ = EndpointStage.objects.update_or_create(
    name="Mission Leben Zentral Android - Endpoint prüfen",
    defaults={"connector": connector, "mode": StageMode.REQUIRED},
)
endpoint_binding, _ = FlowStageBinding.objects.update_or_create(
    target=authentication_flow,
    stage=endpoint_stage,
    defaults={
        "order": 20,
        "evaluate_on_plan": False,
        "re_evaluate_policies": True,
    },
)
PolicyBinding.objects.update_or_create(
    target=endpoint_binding,
    policy=portal_request_policy,
    defaults={"order": 0, "enabled": True, "negate": False},
)

deny_stage, _ = DenyStage.objects.update_or_create(
    name="Mission Leben Zentral Android - Gerätezugriff abgelehnt",
    defaults={
        "deny_message": "Dieses Gerät oder dieser Benutzer ist für Mission Leben Zentral nicht freigegeben."
    },
)
deny_binding, _ = FlowStageBinding.objects.update_or_create(
    target=authentication_flow,
    stage=deny_stage,
    defaults={
        "order": 21,
        "evaluate_on_plan": False,
        "re_evaluate_policies": True,
    },
)
PolicyBinding.objects.update_or_create(
    target=deny_binding,
    policy=deny_device_policy,
    defaults={"order": 0, "enabled": True, "negate": False},
)

# A valid authentik browser cookie is not sufficient for this public OIDC
# client. The provider-specific authorization flow always performs another
# signed endpoint challenge and validates the authenticated user against the
# device's direct personal binding or facility group binding.
authorization_endpoint_binding, _ = FlowStageBinding.objects.update_or_create(
    target=authorization_flow,
    stage=endpoint_stage,
    defaults={
        "order": 10,
        "evaluate_on_plan": False,
        "re_evaluate_policies": True,
    },
)
if authorization_endpoint_binding.policies.exists():
    raise RuntimeError(
        "The Android authorization endpoint binding must run unconditionally"
    )
authorization_deny_binding, _ = FlowStageBinding.objects.update_or_create(
    target=authorization_flow,
    stage=deny_stage,
    defaults={
        "order": 11,
        "evaluate_on_plan": False,
        "re_evaluate_policies": True,
    },
)
unexpected_authorization_deny_policies = PolicyBinding.objects.filter(
    target=authorization_deny_binding
).exclude(
    policy=deny_oidc_device_policy
)
if unexpected_authorization_deny_policies.exists():
    raise RuntimeError(
        "The Android authorization deny binding has unexpected policies; review them manually"
    )
PolicyBinding.objects.update_or_create(
    target=authorization_deny_binding,
    policy=deny_oidc_device_policy,
    defaults={"order": 0, "enabled": True, "negate": False},
)
unexpected_authorization_bindings = FlowStageBinding.objects.filter(
    target=authorization_flow
).exclude(pk__in=(authorization_endpoint_binding.pk, authorization_deny_binding.pk))
if unexpected_authorization_bindings.exists():
    raise RuntimeError(
        "Android authorization flow has unexpected stage bindings; review them manually"
    )

# Password remains mandatory for the first login, shared tablets and personal
# users without TOTP. At the explicit 90-day renewal only, a bound personal
# device with an already configured TOTP authenticator uses that TOTP instead.
password_stage = PasswordStage.objects.get(name=PASSWORD_STAGE_NAME)
password_binding, _ = FlowStageBinding.objects.update_or_create(
    target=authentication_flow,
    stage=password_stage,
    defaults={
        "order": 30,
        "evaluate_on_plan": False,
        "re_evaluate_policies": True,
    },
)
PolicyBinding.objects.update_or_create(
    target=password_binding,
    policy=reauthentication_totp_policy,
    defaults={"order": 30, "enabled": True, "negate": True},
)

# This stage never configures TOTP. It runs only during the 90-day renewal when
# the bound personal user already owns a confirmed TOTP authenticator.
totp_stage = AuthenticatorValidateStage.objects.filter(name=ANDROID_TOTP_STAGE_NAME).first()
if totp_stage is None:
    totp_stage = AuthenticatorValidateStage.objects.filter(
        name=LEGACY_ANDROID_TOTP_STAGE_NAME
    ).first()
if totp_stage is None:
    totp_stage = AuthenticatorValidateStage(name=ANDROID_TOTP_STAGE_NAME)
totp_stage.name = ANDROID_TOTP_STAGE_NAME
totp_stage.not_configured_action = NotConfiguredAction.SKIP
totp_stage.device_classes = [DeviceClasses.TOTP]
totp_stage.last_auth_threshold = "seconds=0"
totp_stage.save()
totp_stage.configuration_stages.clear()
legacy_totp_stages = AuthenticatorValidateStage.objects.filter(
    name=LEGACY_ANDROID_TOTP_STAGE_NAME
).exclude(pk=totp_stage.pk)
FlowStageBinding.objects.filter(
    target=authentication_flow,
    stage__in=legacy_totp_stages,
).delete()
legacy_totp_stages.delete()
totp_binding, _ = FlowStageBinding.objects.update_or_create(
    target=authentication_flow,
    stage=totp_stage,
    defaults={
        "order": 40,
        "evaluate_on_plan": False,
        "re_evaluate_policies": True,
    },
)
PolicyBinding.objects.filter(target=totp_binding).exclude(
    policy=reauthentication_totp_policy
).delete()
PolicyBinding.objects.update_or_create(
    target=totp_binding,
    policy=reauthentication_totp_policy,
    defaults={"order": 10, "enabled": True, "negate": False},
)
ExpressionPolicy.objects.filter(
    name="Mission Leben Zentral Android - TOTP erforderlich"
).delete()
ExpressionPolicy.objects.filter(name=LEGACY_REAUTHENTICATION_POLICY_NAME).exclude(
    pk=reauthentication_totp_policy.pk
).delete()

# Personal devices keep only the Authentik browser SSO cookie across WebView
# process restarts. The OAuth refresh token remains separately protected by the
# Android biometric vault. Zimbra has a dedicated authentication flow, so the
# personal session stage must be present in both relevant flows. Shared devices
# continue through each flow's existing login stage and clear all WebView data
# between employees.
personal_session_stage, _ = UserLoginStage.objects.update_or_create(
    name=PERSONAL_SESSION_STAGE_NAME,
    defaults={
        "session_duration": PERSONAL_SESSION_DURATION,
        "remember_me_offset": "seconds=0",
        "remember_device": "seconds=0",
        "terminate_other_sessions": False,
    },
)
shared_session_stage, _ = UserLoginStage.objects.update_or_create(
    name=ANDROID_SHARED_SESSION_STAGE_NAME,
    defaults={
        "session_duration": "seconds=0",
        "remember_me_offset": "seconds=0",
        "remember_device": "seconds=0",
        "terminate_other_sessions": False,
    },
)
FlowStageBinding.objects.update_or_create(
    target=authentication_flow,
    stage=shared_session_stage,
    defaults={
        "order": 99,
        "evaluate_on_plan": False,
        "re_evaluate_policies": True,
    },
)
personal_session_bindings = []
for flow_slug in PERSONAL_SESSION_FLOW_SLUGS:
    session_flow = Flow.objects.get(slug=flow_slug)
    personal_session_binding, _ = FlowStageBinding.objects.update_or_create(
        target=session_flow,
        stage=personal_session_stage,
        defaults={
            "order": 98,
            "evaluate_on_plan": False,
            "re_evaluate_policies": True,
        },
    )
    personal_session_bindings.append(personal_session_binding)
    PolicyBinding.objects.update_or_create(
        target=personal_session_binding,
        policy=personal_webview_policy,
        defaults={"order": 0, "enabled": True, "negate": False},
    )

    # A flow may have differently named login stages (Zimbra currently has two).
    # Exclude every existing alternative in personal mode so exactly the bounded
    # personal stage creates the Authentik session cookie.
    for login_binding in FlowStageBinding.objects.filter(
        target=session_flow,
        stage__in=UserLoginStage.objects.exclude(pk=personal_session_stage.pk),
    ):
        PolicyBinding.objects.update_or_create(
            target=login_binding,
            policy=personal_webview_policy,
            defaults={"order": 20, "enabled": True, "negate": True},
        )

print(
    "ML_ENDPOINT_BOOTSTRAP="
    + json.dumps(
        {
            "application": application.slug,
            "client_id": provider.client_id,
            "connector": str(connector.pk),
            "endpoint_stage": str(endpoint_stage.pk),
            "authentication_flow": authentication_flow.slug,
            "authorization_flow": authorization_flow.slug,
            "identification_stage": str(identification_stage.pk),
            "totp_stage": str(totp_stage.pk),
            "personal_session_stage": str(personal_session_stage.pk),
            "personal_session_duration": PERSONAL_SESSION_DURATION,
            "refresh_token_validity": REFRESH_TOKEN_VALIDITY,
            "refresh_token_renewal_threshold": REFRESH_TOKEN_RENEWAL_THRESHOLD,
            "personal_session_flows": list(PERSONAL_SESSION_FLOW_SLUGS),
            "mobile_application_group": MOBILE_APPLICATION_GROUP,
            "mobile_application_slugs": list(MOBILE_APPLICATION_SLUGS),
            "feature_scope": FEATURE_SCOPE_NAME,
            "feature_groups": {
                "open_talk": talk_handoff_group.name,
                "device_profile_switch": profile_switch_group.name,
            },
            "device_profile_switch_pilot_configured": profile_switch_pilot is not None,
            "application_access": "active endpoint-bound authentik users",
        },
        sort_keys=True,
    )
)
