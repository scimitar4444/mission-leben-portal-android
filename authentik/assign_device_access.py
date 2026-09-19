"""Assign an enrolled Android endpoint to one user or one facility group.

Run inside the Authentik server container with ``ak shell``. Required environment
variables:

* ``ML_DEVICE_UUID``
* ``ML_DEVICE_MODE``: ``personal`` or ``shared``
* ``ML_AUTHENTIK_USERNAME`` for personal mode
* ``ML_AUTHENTIK_GROUP`` for shared mode

No token or secret is read or printed.
"""

import json
import os

from django.db import transaction

from authentik.core.models import Group, User
from authentik.endpoints.models import Device, DeviceAccessGroup, DeviceUserBinding


PERSONAL_ACCESS_GROUP_PREFIX = "Mission Leben Android - Personal - "
SHARED_ACCESS_GROUP_PREFIX = "Mission Leben Android - Shared - "


def required(name: str) -> str:
    value = os.environ.get(name, "").strip()
    if not value:
        raise RuntimeError(f"Missing required environment variable: {name}")
    return value


device_uuid = required("ML_DEVICE_UUID")
mode = required("ML_DEVICE_MODE").lower()
if mode not in {"personal", "shared"}:
    raise RuntimeError("ML_DEVICE_MODE must be personal or shared")

with transaction.atomic():
    device = Device.objects.select_for_update().get(pk=device_uuid)

    if mode == "personal":
        username = required("ML_AUTHENTIK_USERNAME")
        user = User.objects.get(username=username)
        if not user.is_active:
            raise RuntimeError("The selected Authentik user is inactive")

        access_group, _ = DeviceAccessGroup.objects.update_or_create(
            name=PERSONAL_ACCESS_GROUP_PREFIX + str(user.uuid),
            defaults={
                "attributes": {
                    "mission-leben.de/purpose": "android-portal",
                    "mission-leben.de/status": "active",
                    "mission-leben.de/mode": "personal",
                    "mission-leben.de/user-uuid": str(user.uuid),
                    "mission-leben.de/username": user.username,
                }
            },
        )
        DeviceUserBinding.objects.filter(target=access_group).delete()
        DeviceUserBinding.objects.create(
            target=access_group,
            user=user,
            order=0,
            enabled=True,
            negate=False,
            is_primary=True,
        )
        DeviceUserBinding.objects.filter(target=device).delete()
        principal = user.username
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
        DeviceUserBinding.objects.filter(target=device).delete()
        principal = group.name

    device.access_group = access_group
    device.save(update_fields=["access_group"])

print(
    "ML_DEVICE_ASSIGNMENT="
    + json.dumps(
        {
            "device_uuid": str(device.pk),
            "device_name": device.name,
            "mode": mode,
            "principal": principal,
            "access_group": access_group.name,
        },
        sort_keys=True,
    )
)
