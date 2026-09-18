"""Idempotent Authentik 2026.8 bootstrap for Mission Leben Zentral Android.

Run inside the Authentik server container with ``ak shell`` after a PostgreSQL backup.
No enrollment token or secret is printed or stored by this script.
"""

import json

from authentik.core.models import Application, Group
from authentik.crypto.builder import CertificateBuilder, PrivateKeyAlg
from authentik.crypto.models import CertificateKeyPair
from authentik.endpoints.connectors.agent.models import AgentConnector
from authentik.endpoints.models import (
    DeviceAccessGroup,
    DeviceUserBinding,
    EndpointStage,
    StageMode,
)
from authentik.flows.models import Flow, FlowStageBinding
from authentik.policies.expression.models import ExpressionPolicy
from authentik.policies.models import PolicyBinding
from authentik.providers.oauth2.models import (
    ClientType,
    GrantType,
    OAuth2Provider,
    RedirectURIMatchingMode,
    RedirectURIType,
    ScopeMapping,
)
from authentik.stages.deny.models import DenyStage


CONNECTOR_NAME = "Mission Leben Android"
ACCESS_GROUP_NAME = "Mission Leben Android - Pilot"
CERTIFICATE_NAME = "Mission Leben Android Endpoint Challenge"
AUTHENTICATION_FLOW_SLUG = "mission-leben-browser-authentication"
AUTHORIZATION_FLOW_SLUG = "default-provider-authorization-implicit-consent"
INVALIDATION_FLOW_SLUG = "default-provider-invalidation-flow"
PILOT_GROUP_NAME = "authentik Admins"
PROVIDER_NAME = "Provider for Mission Leben Zentral Android"
APPLICATION_NAME = "Mission Leben Zentral Android"
APPLICATION_SLUG = "mission-leben-portal"
CLIENT_ID = "mission-leben-android"
REDIRECT_URI = "de.missionleben.portal:/oauth2redirect"


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
if device is None or pending_user is None or device.is_expired:
    return True

from authentik.endpoints.connectors.agent.auth import check_device_policies
return not check_device_policies(device, pending_user, http_request).passing
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

device_access_group, _ = DeviceAccessGroup.objects.update_or_create(
    name=ACCESS_GROUP_NAME,
    defaults={
        "attributes": {
            "mission-leben.de/purpose": "android-portal",
            "mission-leben.de/status": "pilot",
        }
    },
)
pilot_group = Group.objects.get(name=PILOT_GROUP_NAME)
DeviceUserBinding.objects.update_or_create(
    target=device_access_group,
    group=pilot_group,
    defaults={"order": 0, "enabled": True, "negate": False, "is_primary": True},
)

authentication_flow = Flow.objects.get(slug=AUTHENTICATION_FLOW_SLUG)
authorization_flow = Flow.objects.get(slug=AUTHORIZATION_FLOW_SLUG)
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
                "url": REDIRECT_URI,
                "matching_mode": RedirectURIMatchingMode.STRICT,
                "redirect_uri_type": RedirectURIType.AUTHORIZATION,
            }
        ],
        "access_code_validity": "minutes=1",
        "access_token_validity": "minutes=5",
        "refresh_token_validity": "days=30",
        "signing_key": signing_key,
    },
)
provider.property_mappings.set(
    ScopeMapping.objects.filter(
        scope_name__in=["openid", "profile", "email", "offline_access", "goauthentik.io/api"],
        name__startswith="authentik default OAuth Mapping:",
    )
)

application, _ = Application.objects.update_or_create(
    slug=APPLICATION_SLUG,
    defaults={
        "name": APPLICATION_NAME,
        "provider": provider,
        "meta_description": "Sicherer Android-Zugang zu den freigegebenen Mission-Leben-Anwendungen",
        "meta_publisher": "Mission Leben",
        "meta_hide": True,
    },
)
PolicyBinding.objects.update_or_create(
    target=application,
    group=pilot_group,
    defaults={"order": 0, "enabled": True, "negate": False},
)

portal_request_policy, _ = ExpressionPolicy.objects.update_or_create(
    name="Mission Leben Zentral Android - App-Anfrage",
    defaults={"expression": PORTAL_REQUEST_EXPRESSION},
)
deny_device_policy, _ = ExpressionPolicy.objects.update_or_create(
    name="Mission Leben Zentral Android - Gerätezugriff verweigern",
    defaults={"expression": DENY_DEVICE_ACCESS_EXPRESSION},
)

endpoint_stage, _ = EndpointStage.objects.update_or_create(
    name="Mission Leben Zentral Android - Endpoint prüfen",
    defaults={"connector": connector, "mode": StageMode.REQUIRED},
)
endpoint_binding, _ = FlowStageBinding.objects.update_or_create(
    target=authentication_flow,
    stage=endpoint_stage,
    defaults={
        "order": 45,
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
        "order": 46,
        "evaluate_on_plan": False,
        "re_evaluate_policies": True,
    },
)
PolicyBinding.objects.update_or_create(
    target=deny_binding,
    policy=deny_device_policy,
    defaults={"order": 0, "enabled": True, "negate": False},
)

print(
    "ML_ENDPOINT_BOOTSTRAP="
    + json.dumps(
        {
            "application": application.slug,
            "client_id": provider.client_id,
            "connector": str(connector.pk),
            "device_access_group": str(device_access_group.pk),
            "endpoint_stage": str(endpoint_stage.pk),
            "pilot_group": pilot_group.name,
        },
        sort_keys=True,
    )
)
