"""Create a short-lived, mode-specific Authentik Android enrollment token.

The caller must redirect stdout to a root-only file; the final output line is
the token key. Required environment variables:

* ``ML_DEVICE_MODE``: ``personal`` or ``shared``
* ``ML_AUTHENTIK_USERNAME`` for personal mode
* ``ML_AUTHENTIK_GROUP`` for shared mode

Personal devices fail closed until ``assign_device_access.py`` binds the newly
created Device UUID to the named user. Shared tokens are tied to the selected
facility group before enrollment.
"""

import os
from datetime import timedelta

from django.utils.timezone import now

from authentik.core.models import Group, User
from authentik.endpoints.connectors.agent.models import AgentConnector, EnrollmentToken
from authentik.endpoints.models import DeviceAccessGroup, DeviceUserBinding


CONNECTOR_NAME = "Mission Leben Android"
PERSONAL_ACCESS_GROUP_NAME = "Mission Leben Android - Personal"
SHARED_ACCESS_GROUP_PREFIX = "Mission Leben Android - Shared - "


def required(name: str) -> str:
    value = os.environ.get(name, "").strip()
    if not value:
        raise RuntimeError(f"Missing required environment variable: {name}")
    return value


mode = required("ML_DEVICE_MODE").lower()
if mode not in {"personal", "shared"}:
    raise RuntimeError("ML_DEVICE_MODE must be personal or shared")

connector = AgentConnector.objects.get(name=CONNECTOR_NAME)
if mode == "personal":
    username = required("ML_AUTHENTIK_USERNAME")
    user = User.objects.get(username=username)
    if not user.is_active:
        raise RuntimeError("The selected Authentik user is inactive")
    access_group = DeviceAccessGroup.objects.get(name=PERSONAL_ACCESS_GROUP_NAME)
    token_name = f"Mission Leben Android personal {user.username}"
else:
    group_name = required("ML_AUTHENTIK_GROUP")
    group = Group.objects.get(name=group_name)
    group_type = group.attributes.get("iam_group_type")
    if not group.name.startswith("ORG_") or group_type not in {
        "organization_house",
        "organization_unit",
    }:
        raise RuntimeError(
            "Shared tablets must be assigned to an ORG_* facility or organization-unit group"
        )
    access_group, _ = DeviceAccessGroup.objects.update_or_create(
        name=SHARED_ACCESS_GROUP_PREFIX + group.name,
        defaults={
            "attributes": {
                "mission-leben.de/purpose": "android-portal",
                "mission-leben.de/status": "active",
                "mission-leben.de/mode": "shared",
                "mission-leben.de/facility-group": group.name,
            }
        },
    )
    DeviceUserBinding.objects.filter(target=access_group).delete()
    DeviceUserBinding.objects.create(
        target=access_group,
        group=group,
        order=0,
        enabled=True,
        negate=False,
        is_primary=True,
    )
    token_name = f"Mission Leben Android shared {group.name}"

token = EnrollmentToken.objects.create(
    name=token_name,
    connector=connector,
    device_group=access_group,
    expiring=True,
    expires=now() + timedelta(hours=24),
)
print(token.key)
