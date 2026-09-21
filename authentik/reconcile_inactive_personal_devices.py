"""Reconcile inactive Authentik users with personal Mission Leben devices.

Run inside the Authentik server container with ``ak shell``. The script is a
read-only dry run unless ``ML_OFFBOARD_RECONCILE_APPLY=1`` is set. Device and
binding records remain in Authentik for traceability; only active access and
tokens are revoked.
"""

from __future__ import annotations

import hashlib
import json
import os
from typing import Any

from django.contrib.sessions.models import Session
from django.db import transaction
from django.utils import timezone

from authentik.core.models import Token, User
from authentik.endpoints.models import Device, DeviceAccessGroup, DeviceUserBinding
from authentik.providers.oauth2.models import AccessToken, DeviceToken, RefreshToken
from authentik.stages.authenticator_duo.models import AuthenticatorDuoStage, DuoDevice


APPLY = os.getenv("ML_OFFBOARD_RECONCILE_APPLY", "").strip() == "1"
PURPOSE = "android-portal"
MODE = "personal"
APP_APPROVAL_STAGE_NAME = "Mission Leben Zentral - App-Bestätigung"
USER_DEVICE_ATTRIBUTE = "mission-leben.de/devices"


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


def _device_status(device: Device) -> str:
    attributes = device.attributes or {}
    if attributes.get("mission-leben.de/status") == "disabled":
        return "disabled"
    if device.expiring and device.expires is not None and device.expires <= timezone.now():
        return "expired"
    return "active"


def _device_summary(devices: dict[str, Device]) -> list[dict[str, str]]:
    return [
        {
            "device_uuid": device_id,
            "name": device.name,
            "status": _device_status(device),
        }
        for device_id, device in sorted(
            devices.items(),
            key=lambda item: (item[1].name.casefold(), item[0]),
        )
    ]


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
    "disabled_devices": 0,
    "disabled_device_ids": [],
    "disabled_device_locks": [],
    "user_device_summaries_updated": 0,
    "user_device_summaries_cleared": 0,
    "app_approval_devices_removed": 0,
    "app_approval_devices_kept": 0,
}

bindings = DeviceUserBinding.objects.filter(
    negate=False,
    user__isnull=False,
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

result["inactive_users"] = sum(1 for user in users.values() if not user.is_active)

with transaction.atomic():
    devices_by_user: dict[int, dict[str, Device]] = {}
    for user_pk, user in users.items():
        user_devices: dict[str, Device] = {}
        for group in groups_by_user[user_pk].values():
            for device in Device.objects.filter(access_group=group):
                user_devices[str(device.device_uuid)] = device
        devices_by_user[user_pk] = user_devices

        if user.is_active:
            for device_id, device in user_devices.items():
                attributes = device.attributes or {}
                explicitly_disabled = attributes.get("mission-leben.de/status") == "disabled"
                expired = bool(
                    device.expiring
                    and device.expires is not None
                    and device.expires <= timezone.now()
                )
                if explicitly_disabled or expired:
                    result["disabled_device_ids"].append(device_id)
                    lock_state = json.dumps(
                        {
                            "disabled_at": attributes.get("mission-leben.de/disabled-at", ""),
                            "expires": device.expires.isoformat() if device.expires else "",
                            "status": attributes.get("mission-leben.de/status", ""),
                        },
                        separators=(",", ":"),
                        sort_keys=True,
                    )
                    result["disabled_device_locks"].append(
                        {
                            "device_id": device_id,
                            "lock_key": hashlib.sha256(
                                f"{device_id}\n{lock_state}".encode()
                            ).hexdigest(),
                        }
                    )
        else:
            subject = str(user.uid or "").strip()
            if subject:
                result["inactive_subjects"].append(subject)
            else:
                result["subjects_missing"] += 1

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

        user_attributes = dict(user.attributes or {})
        summary = _device_summary(user_devices)
        if user_attributes.get(USER_DEVICE_ATTRIBUTE) != summary:
            result["user_device_summaries_updated"] += 1
            if APPLY:
                user_attributes[USER_DEVICE_ATTRIBUTE] = summary
                user.attributes = user_attributes
                user.save(update_fields=["attributes"])

    stale_users = User.objects.filter(attributes__has_key=USER_DEVICE_ATTRIBUTE).exclude(
        pk__in=users
    )
    for user in stale_users:
        result["user_device_summaries_cleared"] += 1
        if APPLY:
            user_attributes = dict(user.attributes or {})
            user_attributes.pop(USER_DEVICE_ATTRIBUTE, None)
            user.attributes = user_attributes
            user.save(update_fields=["attributes"])

    approval_stage = AuthenticatorDuoStage.objects.filter(
        name=APP_APPROVAL_STAGE_NAME
    ).first()
    if approval_stage is not None:
        active_user_ids = {
            user_pk
            for user_pk, user in users.items()
            if user.is_active
            and any(
                _device_status(device) == "active"
                for device in devices_by_user[user_pk].values()
            )
        }
        for approval_device in DuoDevice.objects.filter(stage=approval_stage):
            if approval_device.user_id in active_user_ids:
                result["app_approval_devices_kept"] += 1
                continue
            result["app_approval_devices_removed"] += 1
            if APPLY:
                approval_device.delete()

result["inactive_subjects"].sort()
result["disabled_device_ids"] = sorted(set(result["disabled_device_ids"]))
result["disabled_device_locks"] = sorted(
    {item["device_id"]: item for item in result["disabled_device_locks"]}.values(),
    key=lambda item: item["device_id"],
)
result["disabled_devices"] = len(result["disabled_device_ids"])
print("ML_OFFBOARD_RECONCILE=" + json.dumps(result, separators=(",", ":"), sort_keys=True))
