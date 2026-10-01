"""Reconcile inactive Authentik users with personal Mission Leben devices.

Run inside the Authentik server container with ``ak shell``. The script is a
read-only dry run unless ``ML_OFFBOARD_RECONCILE_APPLY=1`` is set. Existing
device records remain in Authentik for traceability. A personal access group is
removed only after its last device has already been removed, no enrollment is
pending and its direct user binding is still structurally unambiguous.
"""

from __future__ import annotations

import hashlib
import json
import os
from typing import Any

from django.contrib.sessions.models import Session
from django.db import transaction
from django.db.models import Q
from django.utils import timezone
from django.utils.dateparse import parse_datetime

from authentik.core.models import Token, User
from authentik.endpoints.connectors.agent.models import EnrollmentToken
from authentik.endpoints.models import Device, DeviceAccessGroup, DeviceUserBinding
from authentik.policies.models import PolicyBinding
from authentik.providers.oauth2.models import AccessToken, DeviceToken, RefreshToken
from authentik.stages.authenticator_duo.models import AuthenticatorDuoStage, DuoDevice


APPLY = os.getenv("ML_OFFBOARD_RECONCILE_APPLY", "").strip() == "1"
PURPOSE = "android-portal"
MODE = "personal"
PERSONAL_ENROLLMENT_PROFILE = "personal-employee"
APP_APPROVAL_STAGE_NAME = "Mission Leben Zentral - App-Bestätigung"
USER_DEVICE_ATTRIBUTE = "mission-leben.de/devices"
PENDING_ENROLLMENT_ATTRIBUTE = "mission-leben.de/enrollment-pending-until"


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


def _account_eligible_for_app_approval(user) -> bool:
    attributes = user.attributes or {}
    return bool(
        user.is_active
        and user.type != "service_account"
        and attributes.get("iam_account_kind") == "person"
        and attributes.get("iam_directory_class") == "person"
    )


def _binding_is_active(binding: DeviceUserBinding) -> bool:
    return bool(binding.enabled and not binding.is_expired)


def _regular_personal_group(binding: DeviceUserBinding) -> DeviceAccessGroup | None:
    target = binding.target
    if not isinstance(target, DeviceAccessGroup):
        return None
    attributes = target.attributes or {}
    if attributes.get("mission-leben.de/purpose") != PURPOSE:
        return None
    if attributes.get("mission-leben.de/mode") != MODE:
        return None
    if attributes.get("mission-leben.de/handset-profile") not in (None, ""):
        return None
    if attributes.get("mission-leben.de/status") == "disabled":
        return None
    if not (
        _binding_is_active(binding)
        and not binding.negate
        and binding.user_id is not None
        and binding.group_id is None
        and binding.policy_id is None
    ):
        return None
    user_uuid = attributes.get("mission-leben.de/user-uuid")
    if user_uuid not in (None, "") and str(user_uuid) != str(binding.user.uuid):
        return None
    active_bindings = [
        item
        for item in DeviceUserBinding.objects.filter(target=target)
        if _binding_is_active(item)
    ]
    if len(active_bindings) != 1 or active_bindings[0].pk != binding.pk:
        return None
    return target


def _regular_personal_device(device: Device) -> bool:
    attributes = device.attributes or {}
    if _device_status(device) != "active":
        return False
    if attributes.get("mission-leben.de/handset-profile") not in (None, ""):
        return False
    for key in ("mission-leben.de/enrollment-profile", "enrollment_profile"):
        if attributes.get(key) not in (None, "", PERSONAL_ENROLLMENT_PROFILE):
            return False
    return True


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


def _pending_enrollment_state(group: DeviceAccessGroup) -> str:
    raw_value = (group.attributes or {}).get(PENDING_ENROLLMENT_ATTRIBUTE)
    if not raw_value:
        return "none"
    pending_until = parse_datetime(str(raw_value))
    if pending_until is None or timezone.is_naive(pending_until):
        return "invalid"
    return "active" if pending_until > timezone.now() else "expired"


def _has_open_enrollment_token(group: DeviceAccessGroup) -> bool:
    return EnrollmentToken.objects.filter(device_group=group).filter(
        Q(expiring=False) | Q(expires__isnull=True) | Q(expires__gt=timezone.now())
    ).exists()


def _has_expected_personal_binding(group: DeviceAccessGroup) -> bool:
    # Never delete a custom native policy graph merely because the filtered
    # DeviceUserBinding relation still contains one direct user. Exception
    # gates (and other additional bindings) must survive empty-device cleanup.
    if PolicyBinding.objects.filter(target=group).count() != 1:
        return False
    bindings = list(DeviceUserBinding.objects.filter(target=group))
    if len(bindings) != 1:
        return False
    binding = bindings[0]
    return bool(
        binding.enabled
        and not binding.negate
        and binding.user_id is not None
        and binding.group_id is None
        and binding.policy_id is None
    )


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
    "app_approval_devices_removed_by_reason": {},
    "app_approval_devices_kept": 0,
    "personal_groups_seen": 0,
    "empty_personal_groups_removed": 0,
    "empty_personal_groups_kept_pending": 0,
    "empty_personal_groups_kept_open_enrollment": 0,
    "empty_personal_groups_kept_invalid_pending_marker": 0,
    "empty_personal_groups_kept_unexpected_binding": 0,
    "expired_enrollment_tokens_removed": 0,
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
        approval_bound_user_ids: set[int] = set()
        approval_active_user_ids: set[int] = set()
        for binding in DeviceUserBinding.objects.filter(
            enabled=True,
            negate=False,
            user__isnull=False,
        ).select_related("user"):
            group = _regular_personal_group(binding)
            if group is None or not _account_eligible_for_app_approval(binding.user):
                continue
            user_pk = int(binding.user_id)
            approval_bound_user_ids.add(user_pk)
            if any(
                _regular_personal_device(device)
                for device in Device.objects.filter(access_group=group)
            ):
                approval_active_user_ids.add(user_pk)

        removal_reasons: dict[str, int] = {}
        for approval_device in DuoDevice.objects.filter(stage=approval_stage):
            user = approval_device.user
            reason = ""
            if not _account_eligible_for_app_approval(user):
                reason = "ineligible-account"
            elif approval_device.duo_user_id != str(user.uid):
                reason = "subject-mismatch"
            elif approval_device.user_id not in approval_bound_user_ids:
                reason = "no-unambiguous-personal-binding"
            elif approval_device.user_id not in approval_active_user_ids:
                reason = "no-active-regular-personal-device"
            if not reason:
                result["app_approval_devices_kept"] += 1
                continue
            result["app_approval_devices_removed"] += 1
            removal_reasons[reason] = removal_reasons.get(reason, 0) + 1
            if APPLY:
                approval_device.delete()
        result["app_approval_devices_removed_by_reason"] = removal_reasons

    # Personal access groups are created before a QR code is issued so the
    # endpoint is never briefly unbound. Remove only genuinely empty groups.
    # Shared facility groups are deliberately reusable and are never handled
    # here.
    for group in DeviceAccessGroup.objects.all():
        attributes = group.attributes or {}
        if attributes.get("mission-leben.de/purpose") != PURPOSE:
            continue
        if attributes.get("mission-leben.de/mode") != MODE:
            continue
        result["personal_groups_seen"] += 1
        if Device.objects.filter(access_group=group).exists():
            continue

        pending_state = _pending_enrollment_state(group)
        if pending_state == "active":
            result["empty_personal_groups_kept_pending"] += 1
            continue
        if pending_state == "invalid":
            result["empty_personal_groups_kept_invalid_pending_marker"] += 1
            continue
        if _has_open_enrollment_token(group):
            result["empty_personal_groups_kept_open_enrollment"] += 1
            continue
        if not _has_expected_personal_binding(group):
            result["empty_personal_groups_kept_unexpected_binding"] += 1
            continue

        expired_tokens = EnrollmentToken.objects.filter(device_group=group)
        result["expired_enrollment_tokens_removed"] += _count_and_delete(expired_tokens)
        result["empty_personal_groups_removed"] += 1
        if APPLY:
            group.delete()

result["inactive_subjects"].sort()
result["disabled_device_ids"] = sorted(set(result["disabled_device_ids"]))
result["disabled_device_locks"] = sorted(
    {item["device_id"]: item for item in result["disabled_device_locks"]}.values(),
    key=lambda item: item["device_id"],
)
result["disabled_devices"] = len(result["disabled_device_ids"])
print("ML_OFFBOARD_RECONCILE=" + json.dumps(result, separators=(",", ":"), sort_keys=True))
