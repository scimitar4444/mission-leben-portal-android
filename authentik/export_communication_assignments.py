"""Export communication assignments for already registered mobile subjects.

Run inside the Authentik server container with ``ak shell``. Authentik groups
remain authoritative; the output is consumed locally by the bridge host and is
never exposed to Android clients.
"""

from __future__ import annotations

import json
import os

from authentik.core.models import Application, Group, User
from authentik.policies.engine import PolicyEngine


ZIMBRA_GROUP = "APP_ZIMBRA_USER"
TALK_GROUPS = {"APP_NEXTCLOUD_USER", "APP_NEXTCLOUD_NATIVE"}
REQUIRED_GROUPS = {ZIMBRA_GROUP, *TALK_GROUPS}
ZIMBRA_APPLICATION_SLUG = "zimbra-mail"
TALK_APPLICATION_SLUG = "talk"

raw_subjects = json.loads(os.environ.get("ML_COMMUNICATION_SUBJECTS_JSON", "[]"))
if not isinstance(raw_subjects, list) or not all(isinstance(value, str) for value in raw_subjects):
    raise RuntimeError("ML_COMMUNICATION_SUBJECTS_JSON must contain a list of subjects")
subjects = {value.strip() for value in raw_subjects if value.strip()}
if len(subjects) > 5000:
    raise RuntimeError("too many communication subjects")

available_groups = set(
    Group.objects.filter(name__in=REQUIRED_GROUPS).values_list("name", flat=True)
)
missing_groups = REQUIRED_GROUPS - available_groups
if missing_groups:
    raise RuntimeError("missing canonical Authentik groups: " + ", ".join(sorted(missing_groups)))
applications = {
    application.slug: application
    for application in Application.objects.filter(
        slug__in={ZIMBRA_APPLICATION_SLUG, TALK_APPLICATION_SLUG}
    )
}
if set(applications) != {ZIMBRA_APPLICATION_SLUG, TALK_APPLICATION_SLUG}:
    raise RuntimeError("required Authentik communication applications are missing")

assignments = []
users = [
    user
    for user in User.objects.filter(is_active=True).order_by("uuid")
    if str(user.uid) in subjects
]
for user in users:
    group_names = {group.name for group in user.all_groups()}
    zimbra = ZIMBRA_GROUP in group_names and PolicyEngine(
        applications[ZIMBRA_APPLICATION_SLUG], user
    ).build().passing
    talk = bool(TALK_GROUPS & group_names) and PolicyEngine(
        applications[TALK_APPLICATION_SLUG], user
    ).build().passing
    if not zimbra and not talk:
        continue
    attributes = user.attributes or {}
    assignments.append(
        {
            "subject": str(user.uid),
            "email": str(user.email or "").strip().lower(),
            "nextcloud_user_id": str(attributes.get("nextcloudUid") or user.uid).strip(),
            "zimbra": zimbra,
            "talk": talk,
        }
    )

print(
    "ML_COMMUNICATION_ASSIGNMENTS="
    + json.dumps({"assignments": assignments}, ensure_ascii=False, separators=(",", ":"))
)
