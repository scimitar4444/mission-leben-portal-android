"""Reconcile inactive Authentik users with personal Mission Leben devices.

Run inside the Authentik server container with ``ak shell``. The script is a
read-only dry run unless ``ML_OFFBOARD_RECONCILE_APPLY=1`` is set. Device and
binding records remain in Authentik for traceability; only active access and
tokens are revoked.
"""

from __future__ import annotations

import json
import os
from typing import Any

from django.contrib.sessions.models import Session
from django.db import transaction
from django.utils import timezone

from authentik.core.models import Token
from authentik.endpoints.models import Device, DeviceAccessGroup, DeviceUserBinding
from authentik.providers.oauth2.models import AccessToken, DeviceToken, RefreshToken


APPLY = os.getenv("ML_OFFBOARD_RECONCILE_APPLY", "").strip() == "1"
PURPOSE = "android-portal"
MODE = "personal"


def _eligible_group(binding: DeviceUserBinding) -> DeviceAccessGroup | None:
    target = binding.target
    if not isinstance(target, DeviceAccessGroup):
        return None
    attributes = target.attributes or {}
    if attributes.get("mission-leben.de/purpose") != PURPOSE:
        return None
    if attributes.get("mission-leben.de/mode") != MODE:
        return None
    return target


def _count_and_delete(queryset: Any) -> int:
    count = queryset.count()
    if APPLY and count:
        queryset.delete()
    return count


result: dict[str, Any] = {
    "mode": "apply" if APPLY else "dry-run",
    "inactive_users": 0,
    "matching_bindings": 0,
    "devices_found": 0,
    "devices_disabled": 0,
    "sessions_revoked": 0,
    "core_tokens_revoked": 0,
    "oauth_tokens_revoked": 0,
    "subjects_missing": 0,
    "inactive_subjects": [],
}

bindings = DeviceUserBinding.objects.filter(
    negate=False,
    user__isnull=False,
    user__is_active=False,
).select_related("user")

users: dict[int, Any] = {}
groups_by_user: dict[int, dict[str, DeviceAccessGroup]] = {}
for binding in bindings:
    group = _eligible_group(binding)
    if group is None:
        continue
    user_pk = int(binding.user_id)
    users[user_pk] = binding.user
    groups_by_user.setdefault(user_pk, {})[str(group.pbm_uuid)] = group
    result["matching_bindings"] += 1

result["inactive_users"] = len(users)

with transaction.atomic():
    for user_pk, user in users.items():
        subject = str(user.uid or "").strip()
        if subject:
            result["inactive_subjects"].append(subject)
        else:
            result["subjects_missing"] += 1

        user_devices: dict[str, Device] = {}
        for group in groups_by_user[user_pk].values():
            for device in Device.objects.filter(access_group=group):
                user_devices[str(device.device_uuid)] = device
        result["devices_found"] += len(user_devices)

        if APPLY:
            for device in user_devices.values():
                attributes = dict(device.attributes or {})
                already_disabled = attributes.get("mission-leben.de/status") == "disabled"
                if not already_disabled:
                    attributes["mission-leben.de/status"] = "disabled"
                    attributes["mission-leben.de/disabled-reason"] = "user-inactive"
                    attributes["mission-leben.de/disabled-at"] = timezone.now().isoformat().replace(
                        "+00:00", "Z"
                    )
                    device.attributes = attributes
                    device.expiring = False
                    device.expires = None
                    device.save(update_fields=["attributes", "expiring", "expires"])
                    result["devices_disabled"] += 1

        result["sessions_revoked"] += _count_and_delete(
            Session.objects.filter(authenticatedsession__user=user)
        )
        result["core_tokens_revoked"] += _count_and_delete(
            Token.objects.including_expired().filter(user=user)
        )
        for model in (AccessToken, RefreshToken, DeviceToken):
            result["oauth_tokens_revoked"] += _count_and_delete(
                model.objects.including_expired().filter(user=user)
            )

result["inactive_subjects"].sort()
print("ML_OFFBOARD_RECONCILE=" + json.dumps(result, separators=(",", ":"), sort_keys=True))
