"""Read-only, allowlisted employee projection. Run in Authentik via ak shell.

No credentials, private numbers, HR attributes or full group memberships leave
the host. The internal audience/device maps are never returned to app clients.
"""
from __future__ import annotations

import json
import re
import time


BUSINESS_EMAIL_DOMAINS = {"mission-leben.de", "akademie-mission-leben.de"}

# Address-book projections, NOT new Authentik groups or device locations.
# Canonical objects confirmed by the group owner; geography confirmed by the operator.
ACADEMY_DIRECTORY_SITES = {
    "directory:academy:bad-homburg": ("Akademie Bad Homburg", {
        "ORG_AKA_BAD_HOMBURG": "6cfad1d9-80b5-4ec0-8bcc-4bd21a72c353",
    }),
    "directory:academy:darmstadt": ("Akademie Darmstadt", {
        "ORG_AKA_DARMSTADT_APS": "848ed54c-bbe9-4620-88b0-6a3e1ec66308",
        "ORG_AKA_DARMSTADT_HEP": "c53aa77c-0c8d-4dad-8695-941cf0e81fc0",
        "ORG_AKA_DARMSTADT_IFW": "bd0a6e2e-0464-4667-8f74-c96e9a24711a",
    }),
    "directory:academy:wiesbaden": ("Akademie Wiesbaden", {
        "ORG_AKA_WIESBADEN_APS": "020a75d6-f902-497c-a213-838e36ab9f13",
    }),
}


def text(value, limit=160):
    return " ".join(value.split())[:limit] if isinstance(value, str) else ""


def real_facility(name, attrs):
    return bool(
        re.fullmatch(r"ORG_ML_H[0-9]{3}(?:_[0-9]{2})?", name)
        and attrs.get("iam_group_type") == "organization_unit"
        and attrs.get("iam_managed") is True
        and attrs.get("iam_plan_status") in {"UMGESETZT_UEBERGANG", "AKTIV"}
        and (attrs.get("iam_org_level") in {"Einrichtung", "Einrichtung/Verbund"}
             or (name == "ORG_ML_H001" and attrs.get("iam_org_level") == "Geschäftseinheit/Standort"))
    )


def directory_facilities(groups):
    """Return display options and effective-group -> option IDs, not authorization."""
    facilities, memberships = {}, {}
    for group in groups:
        attrs = group.attributes or {}
        if real_facility(group.name, attrs):
            facilities[group.name] = text(attrs.get("iam_display_name")) or group.name
            memberships[group.name] = group.name
            continue
        for key, (label, expected) in ACADEMY_DIRECTORY_SITES.items():
            if (expected.get(group.name) == str(group.pk)
                    and attrs.get("iam_group_type") == "organization_unit"
                    and attrs.get("iam_managed") is True
                    and attrs.get("iam_plan_status") in {"UMGESETZT_UEBERGANG", "AKTIV"}
                    and attrs.get("iam_org_level") == "Fachbereich/Standort"
                    and attrs.get("iam_parent_group") == "ORG_AKA"
                    and any(parent.name == "ORG_AKA" for parent in group.parents.all())):
                facilities[key] = label
                memberships[group.name] = key
    return facilities, memberships


def contact_facilities(group_names, memberships):
    # Multiple Darmstadt departments still produce one option and one user entry.
    return {memberships[name] for name in group_names if name in memberships}


def account_kind(user):
    attrs = user.attributes or {}
    if not user.is_active or user.type == "service_account":
        return ""
    if attrs.get("iam_account_kind") == "person" and attrs.get("iam_directory_class") == "person":
        return "person"
    if attrs.get("iam_account_kind") == "shared":
        return "shared"
    return ""


def contact_name(user):
    attrs = user.attributes or {}
    known = text(attrs.get("displayName")) or text(user.name)
    if account_kind(user) == "person":
        given, family = text(attrs.get("givenName")), text(attrs.get("sn"))
        # Preserve functional/ambiguous display names even when the upstream
        # classification currently says person. No reclassification here.
        known_forms = {f"{given} {family}".casefold(), f"{family} {given}".casefold(),
                       f"{family}, {given}".casefold(), f"{family},{given}".casefold()}
        if given and family and known.casefold() in known_forms:
            return text(f"{family}, {given}")
    # Functional accounts keep their complete function name. Never guess name
    # parts from spaces, dots, email addresses or usernames.
    return known


def contact(user, facilities):
    attrs = user.attributes or {}
    # Owner confirmed telephoneNumber AND mobile hold company numbers (2026-09-22).
    # Never fall back to homePhone, arbitrary phone_number, notes or addresses.
    phone = text(attrs.get("telephoneNumber"), 64)
    if not re.fullmatch(r"\+?[0-9 ()/.-]{3,60}", phone) or sum(c.isdigit() for c in phone) < 3:
        phone = ""
    mobile = text(attrs.get("mobile"), 64)
    if not re.fullmatch(r"\+?[0-9 ()/.-]{3,60}", mobile) or sum(c.isdigit() for c in mobile) < 3:
        mobile = ""
    if re.sub(r"[^0-9+]", "", phone) == re.sub(r"[^0-9+]", "", mobile):
        mobile = ""
    email = text(user.email, 254)
    if (not re.fullmatch(r"[^\s<>?&#,;]+@[^\s<>?&#,;]+\.[^\s<>?&#,;]+", email)
            or email.rsplit("@", 1)[-1].lower() not in BUSINESS_EMAIL_DOMAINS):
        email = ""
    return {
        "id": str(user.uid), "name": contact_name(user), "email": email,
        # Internal search aliases, not a display-name rewrite and never inferred
        # from usernames/email. Only the explicitly allowed person attributes.
        "search_names": list(dict.fromkeys(filter(None, (
            text(attrs.get("givenName")), text(attrs.get("sn")),
        )))) if account_kind(user) == "person" else [],
        "phone": phone, "mobile": mobile,
        "job_title": text(attrs.get("employee_job_title") or attrs.get("title")),
        "department": text(attrs.get("employee_department") or attrs.get("department")),
        "facilities": sorted(facilities),
    }


def main():
    from authentik.core.models import Group, User
    from authentik.endpoints.models import Device, DeviceUserBinding
    from django.utils import timezone

    groups = list(Group.objects.filter(name__startswith="ORG_").prefetch_related("parents"))
    device_facilities = {
        g.name: text((g.attributes or {}).get("iam_display_name")) or g.name
        for g in groups
        if real_facility(g.name, g.attributes or {})
    }
    facilities, memberships = directory_facilities(groups)
    entries, audience, subjects = [], {}, {}
    for user in User.objects.filter(is_active=True).order_by("pk"):
        kind = account_kind(user)
        if not kind:
            continue
        own = contact_facilities({g.name for g in user.all_groups()}, memberships)
        uid = str(user.uid)
        subjects[user.pk] = uid
        audience[uid] = {"kind": kind, "facilities": sorted(own)}
        if kind == "person":
            entry = contact(user, own)
            if entry["name"]:
                entries.append(entry)

    devices = {}
    for device in Device.objects.select_related("access_group"):
        if (device.attributes or {}).get("mission-leben.de/status") == "disabled":
            continue
        if device.expiring and device.expires and device.expires <= timezone.now():
            continue
        group = device.access_group
        attrs = (group.attributes or {}) if group else {}
        if attrs.get("mission-leben.de/purpose") != "android-portal":
            continue
        bindings = list(DeviceUserBinding.objects.filter(target=group))
        if len(bindings) != 1:
            continue
        binding = bindings[0]
        if not binding.enabled or binding.negate or binding.policy_id is not None:
            continue
        mode = attrs.get("mission-leben.de/mode")
        if mode == "personal" and binding.group_id is None and binding.user_id in subjects:
            subject = subjects[binding.user_id]
            if audience[subject]["kind"] == "person":
                devices[str(device.pk)] = {"mode": mode, "subject": subject}
        elif mode == "shared" and binding.user_id is None and binding.group_id is not None:
            facility = attrs.get("mission-leben.de/facility-group")
            if facility in device_facilities and binding.group.name == facility:
                devices[str(device.pk)] = {"mode": mode, "facility": facility}
    print("ML_EMPLOYEE_DIRECTORY=" + json.dumps({
        "version": 1, "generated_at": int(time.time()), "facilities": facilities,
        "entries": entries, "audience": audience, "devices": devices,
    }, ensure_ascii=False, separators=(",", ":")))


if __name__ == "__main__":
    main()
