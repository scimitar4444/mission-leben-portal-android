"""Backfill searchable assignment labels on Mission Leben Android devices.

Run inside the Authentik server container with ``ak shell``. The canonical
DeviceUserBinding remains the authorization source. This script only copies a
human-readable principal into the device name and attributes for operations.

Dry-run is the default. Set ``ML_APPLY=1`` to save changes.
"""

import json
import os

from django.db import transaction

from authentik.endpoints.models import Device, DeviceUserBinding


apply_changes = os.environ.get("ML_APPLY", "").strip() == "1"
result = {"apply": apply_changes, "changed": 0, "unchanged": 0, "skipped": 0}

with transaction.atomic():
    for device in Device.objects.select_related("access_group").order_by("device_uuid"):
        access_group = device.access_group
        group_attributes = access_group.attributes if access_group else {}
        if group_attributes.get("mission-leben.de/purpose") != "android-portal":
            continue
        mode = group_attributes.get("mission-leben.de/mode")
        bindings = list(
            DeviceUserBinding.objects.filter(
                target=access_group,
                enabled=True,
                negate=False,
                policy__isnull=True,
            ).select_related("user", "group")
        )
        if len(bindings) != 1:
            result["skipped"] += 1
            continue
        binding = bindings[0]
        if mode == "personal" and binding.user_id and not binding.group_id:
            assigned_kind = "user"
            assigned_to = binding.user.username
        elif mode == "shared" and binding.group_id and not binding.user_id:
            assigned_kind = "organization"
            assigned_to = binding.group.name
        else:
            result["skipped"] += 1
            continue

        attributes = dict(device.attributes or {})
        previous_assignment = str(
            attributes.get("mission-leben.de/assigned-to") or ""
        ).strip()
        base_name = device.name
        if previous_assignment and base_name.endswith(" · " + previous_assignment):
            base_name = base_name[: -(len(previous_assignment) + 3)].rstrip()
        display_name = base_name if base_name.endswith(" · " + assigned_to) else (
            base_name + " · " + assigned_to
        )
        expected_attributes = {
            **attributes,
            "mission-leben.de/purpose": "android-portal",
            "mission-leben.de/mode": mode,
            "mission-leben.de/assigned-kind": assigned_kind,
            "mission-leben.de/assigned-to": assigned_to,
        }
        if device.name == display_name and attributes == expected_attributes:
            result["unchanged"] += 1
            continue
        result["changed"] += 1
        if apply_changes:
            device.name = display_name
            device.attributes = expected_attributes
            device.save(update_fields=["name", "attributes"])

    if not apply_changes:
        transaction.set_rollback(True)

print("ML_DEVICE_ASSIGNMENT_BACKFILL=" + json.dumps(result, sort_keys=True))
