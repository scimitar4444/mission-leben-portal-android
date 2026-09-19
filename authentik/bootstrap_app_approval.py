"""Configure Mission Leben app approval as an Authentik MFA option.

Run inside the Authentik server container with ``ak shell`` after a database
backup. The script deliberately reuses Authentik's supported Duo stage and
points it at the narrowly implemented Duo Auth API subset in the communication
bridge. It does not patch Authentik and does not create a second device store.
"""

import json
import os
import re

from authentik.common.oauth.constants import SubModes
from authentik.endpoints.models import Device, DeviceAccessGroup, DeviceUserBinding
from authentik.flows.models import FlowStageBinding
from authentik.providers.oauth2.models import OAuth2Provider
from authentik.stages.authenticator_duo.models import AuthenticatorDuoStage, DuoDevice
from authentik.stages.authenticator_validate.models import (
    AuthenticatorValidateStage,
    DeviceClasses,
)


DUO_STAGE_NAME = "Mission Leben Zentral - App-Bestätigung"
ANDROID_PROVIDER_NAME = "Provider for Mission Leben Zentral Android"
ANDROID_AUTHENTICATION_FLOW_SLUG = "mission-leben-android-authentication"
DEFAULT_VALIDATION_STAGE_NAME = "MFA verpflichtend"
INTEGRATION_KEY_PATTERN = re.compile(r"^[A-Za-z0-9]{20,64}$")
HOSTNAME_PATTERN = re.compile(
    r"^(?=.{1,253}$)(?:[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?\.)+"
    r"[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?$"
)


def required(name: str) -> str:
    value = os.environ.get(name, "").strip()
    if not value:
        raise RuntimeError(f"{name} must be configured")
    return value


integration_key = required("ML_APP_APPROVAL_INTEGRATION_KEY")
secret_key = required("ML_APP_APPROVAL_SECRET_KEY")
api_hostname = required("ML_APP_APPROVAL_API_HOSTNAME").lower()
validation_stage_name = os.environ.get(
    "ML_APP_APPROVAL_VALIDATION_STAGE", DEFAULT_VALIDATION_STAGE_NAME
).strip()

if not INTEGRATION_KEY_PATTERN.fullmatch(integration_key):
    raise RuntimeError(
        "ML_APP_APPROVAL_INTEGRATION_KEY must contain 20 to 64 alphanumeric characters"
    )
if len(secret_key) < 32:
    raise RuntimeError("ML_APP_APPROVAL_SECRET_KEY must contain at least 32 characters")
if not HOSTNAME_PATTERN.fullmatch(api_hostname):
    raise RuntimeError("ML_APP_APPROVAL_API_HOSTNAME must be a hostname without scheme or path")

provider = OAuth2Provider.objects.get(name=ANDROID_PROVIDER_NAME)
if provider.sub_mode != SubModes.HASHED_USER_ID:
    raise RuntimeError(
        "The Android OIDC provider must use hashed_user_id so its subject matches Authentik user.uid"
    )

validation_stage = AuthenticatorValidateStage.objects.get(name=validation_stage_name)
if FlowStageBinding.objects.filter(
    target__slug=ANDROID_AUTHENTICATION_FLOW_SLUG,
    stage=validation_stage,
).exists():
    raise RuntimeError(
        "The central MFA stage must not run in the Android app login flow; that would create a loop"
    )

duo_stage, _ = AuthenticatorDuoStage.objects.update_or_create(
    name=DUO_STAGE_NAME,
    defaults={
        "friendly_name": "Mission Leben Zentral App",
        "configure_flow": None,
        "client_id": integration_key,
        "client_secret": secret_key,
        "api_hostname": api_hostname,
        "admin_integration_key": "",
        "admin_secret_key": "",
    },
)

device_classes = list(validation_stage.device_classes or [])
if DeviceClasses.DUO not in device_classes:
    device_classes.append(DeviceClasses.DUO)
    validation_stage.device_classes = device_classes
    validation_stage.save(update_fields=["device_classes"])

# Link existing personal Endpoint users as well. The Authentik user remains the
# source of truth; duo_user_id is exactly the Android provider's stable OIDC
# subject. Group-bound shared tablets are intentionally excluded.
linked_users = 0
personal_target_ids = {
    group.pbm_uuid
    for group in DeviceAccessGroup.objects.all()
    if group.attributes.get("mission-leben.de/mode") == "personal"
}
personal_target_ids.update(
    device.pbm_uuid
    for device in Device.objects.select_related("access_group").all()
    if device.access_group
    and device.access_group.attributes.get("mission-leben.de/mode") == "personal"
)
personal_bindings = DeviceUserBinding.objects.filter(
    enabled=True,
    user__isnull=False,
    user__is_active=True,
    target_id__in=personal_target_ids,
).select_related("user")
for binding in personal_bindings:
    subject = binding.user.uid
    conflicting = DuoDevice.objects.filter(user=binding.user, stage=duo_stage).exclude(
        duo_user_id=subject
    )
    if conflicting.exists():
        raise RuntimeError(
            f"User {binding.user.username!r} has an app-approval device with a mismatched subject"
        )
    _, created = DuoDevice.objects.update_or_create(
        user=binding.user,
        stage=duo_stage,
        duo_user_id=subject,
        defaults={
            "confirmed": True,
            "name": "Mission Leben Zentral App",
        },
    )
    linked_users += int(created)

print(
    json.dumps(
        {
            "status": "configured",
            "duo_stage_uuid": str(duo_stage.stage_uuid),
            "validation_stage": validation_stage.name,
            "device_classes": list(validation_stage.device_classes),
            "api_hostname": duo_stage.api_hostname,
            "newly_linked_personal_users": linked_users,
        },
        sort_keys=True,
    )
)
