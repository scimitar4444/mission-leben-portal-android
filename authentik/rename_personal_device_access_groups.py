"""Rename personal Android access groups to readable Authentik usernames.

Run inside the Authentik server container with ``ak shell``. The immutable
``mission-leben.de/user-uuid`` attribute and the direct DeviceUserBinding remain
the authorization source. This script only changes the human-facing group name
and refreshes the informational username attribute.

Dry-run is the default. Set ``ML_APPLY=1`` to save changes.
"""

import json
import os

from django.db import transaction

from authentik.core.models import User
from authentik.endpoints.models import DeviceAccessGroup, DeviceUserBinding


PREFIX = "Mission Leben Android - Personal - "
APPLY = os.environ.get("ML_APPLY", "").strip() == "1"
result = {"apply": APPLY, "changed": 0, "unchanged": 0, "skipped": 0}

with transaction.atomic():
    groups = list(DeviceAccessGroup.objects.select_for_update().order_by("name"))
    names = {group.name: group for group in groups}
    for group in groups:
        attributes = dict(group.attributes or {})
        if attributes.get("mission-leben.de/purpose") != "android-portal":
            continue
        if attributes.get("mission-leben.de/mode") != "personal":
            continue
        user_uuid = str(attributes.get("mission-leben.de/user-uuid", "")).strip()
        if not user_uuid:
            continue
        user = User.objects.filter(uuid=user_uuid).first()
        bindings = list(
            DeviceUserBinding.objects.filter(
                target=group,
                enabled=True,
                negate=False,
                policy__isnull=True,
            )
        )
        if (
            user is None
            or len(bindings) != 1
            or bindings[0].user_id != user.pk
            or bindings[0].group_id is not None
        ):
            result["skipped"] += 1
            continue

        desired_name = PREFIX + user.username
        name_owner = names.get(desired_name)
        if name_owner is not None and name_owner.pk != group.pk:
            raise RuntimeError(
                f"Readable device access group name is already in use: {desired_name}"
            )
        expected_attributes = {
            **attributes,
            "mission-leben.de/username": user.username,
        }
        if group.name == desired_name and attributes == expected_attributes:
            result["unchanged"] += 1
            continue
        result["changed"] += 1
        if APPLY:
            names.pop(group.name, None)
            group.name = desired_name
            group.attributes = expected_attributes
            group.save(update_fields=["name", "attributes"])
            names[desired_name] = group

    if not APPLY:
        transaction.set_rollback(True)

print("ML_PERSONAL_ACCESS_GROUP_RENAME=" + json.dumps(result, sort_keys=True))
