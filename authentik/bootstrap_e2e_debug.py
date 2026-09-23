"""Create the isolated Mission Leben Android end-to-end test identity.

Run this script inside the authentik server container with ``ak shell`` only
after a PostgreSQL backup. Password and TOTP key are read from root-only files;
the script never prints either secret.
Required groups must already exist. This script never grants initializer roles;
any such test authorization is a separate group-governance decision.
"""

import json
import os
import re
from pathlib import Path

if os.environ.get("ML_AUTHENTIK_BOOTSTRAP_APPLY") != "1":
    raise RuntimeError("Bootstrap execution requires separate approval and ML_AUTHENTIK_BOOTSTRAP_APPLY=1")

from authentik import VERSION

if VERSION != "2026.8.3":
    raise RuntimeError(f"Unreviewed Authentik version {VERSION}; validate this bootstrap before execution")

from django.db import transaction

from authentik.core.models import Group, User, UserTypes
from authentik.providers.oauth2.models import (
    OAuth2Provider,
    RedirectURIMatchingMode,
    RedirectURIType,
)
from authentik.stages.authenticator_totp.models import TOTPDevice


PROVIDER_NAME = "Provider for Mission Leben Zentral Android"
PRODUCTION_REDIRECT_URI = "de.missionleben.portal:/oauth2redirect"
DEBUG_REDIRECT_URI = "de.missionleben.portal.debug:/oauth2redirect"
TEST_ORGANIZATION = "ORG_E2E_TEST"
INITIALIZER_ROLE = "BR_EINRICHTUNGSLEITUNG"
TOTP_DEVICE_NAME = "Mission Leben Portal E2E"
USERNAME = os.environ.get("ML_E2E_USERNAME", "ml-portal-e2e").strip()
PASSWORD_FILE = Path(os.environ["ML_E2E_PASSWORD_FILE"])
TOTP_KEY_FILE = Path(os.environ["ML_E2E_TOTP_KEY_FILE"])


if not re.fullmatch(r"ml-portal-e2e(?:-[a-z0-9-]+)?", USERNAME):
    raise RuntimeError("ML_E2E_USERNAME must use the reserved ml-portal-e2e prefix")
password = PASSWORD_FILE.read_text(encoding="utf-8").strip()
totp_key = TOTP_KEY_FILE.read_text(encoding="ascii").strip().lower()
if len(password) < 32:
    raise RuntimeError("The E2E password must contain at least 32 characters")
if not re.fullmatch(r"[0-9a-f]{40}", totp_key):
    raise RuntimeError("The E2E TOTP key must be exactly 20 bytes encoded as hex")


with transaction.atomic():
    # Prerequisites are read-only and checked before provider/user changes.
    try:
        initializer_role = Group.objects.get(name=INITIALIZER_ROLE)
        test_organization = Group.objects.get(name=TEST_ORGANIZATION)
    except Group.DoesNotExist as error:
        raise RuntimeError("Required E2E group is missing; contact group governance") from error
    if initializer_role.is_superuser or initializer_role.attributes.get("iam_group_type") != "business_role":
        raise RuntimeError("The E2E initializer role is not a canonical business role")
    if (
        test_organization.is_superuser
        or test_organization.attributes.get("iam_group_type") != "organization_unit"
        or test_organization.attributes.get("mission-leben.de/purpose") != "e2e-test-only"
    ):
        raise RuntimeError("The existing E2E organization is not an isolated test organization")

    provider = OAuth2Provider.objects.select_for_update().get(name=PROVIDER_NAME)
    current_redirects = {
        (str(item.url), item.matching_mode, item.redirect_uri_type)
        for item in provider.redirect_uris
    }
    allowed_redirects = {
        (
            PRODUCTION_REDIRECT_URI,
            RedirectURIMatchingMode.STRICT,
            RedirectURIType.AUTHORIZATION,
        ),
        (
            DEBUG_REDIRECT_URI,
            RedirectURIMatchingMode.STRICT,
            RedirectURIType.AUTHORIZATION,
        ),
    }
    if not current_redirects or not current_redirects.issubset(allowed_redirects):
        raise RuntimeError("The Android provider has unexpected redirect URIs")
    provider._redirect_uris = [
        {
            "url": uri,
            "matching_mode": RedirectURIMatchingMode.STRICT,
            "redirect_uri_type": RedirectURIType.AUTHORIZATION,
        }
        for uri in (PRODUCTION_REDIRECT_URI, DEBUG_REDIRECT_URI)
    ]
    provider.save(update_fields=["_redirect_uris"])

    user, created = User.objects.get_or_create(
        username=USERNAME,
        defaults={
            "name": "Portal E2E Test",
            "email": "",
            "is_active": True,
            "type": UserTypes.INTERNAL,
            "path": "goauthentik.io/testing/mission-leben",
            "attributes": {"mission-leben.de/purpose": "e2e-test-only"},
        },
    )
    if not created and user.attributes.get("mission-leben.de/purpose") != "e2e-test-only":
        raise RuntimeError("The reserved E2E username belongs to an unmanaged account")
    unexpected_groups = set(user.groups.exclude(pk__in=[initializer_role.pk, test_organization.pk]))
    if unexpected_groups:
        raise RuntimeError("The E2E user has unexpected group memberships")
    user.name = "Portal E2E Test"
    user.email = ""
    user.is_active = True
    user.type = UserTypes.INTERNAL
    user.path = "goauthentik.io/testing/mission-leben"
    user.attributes = {**user.attributes, "mission-leben.de/purpose": "e2e-test-only"}
    user.set_password(password)
    user.save()
    # Preserve separately approved existing roles, but never grant/migrate one.
    user.groups.add(test_organization)

    other_totp_devices = TOTPDevice.objects.filter(user=user).exclude(name=TOTP_DEVICE_NAME)
    if other_totp_devices.exists():
        raise RuntimeError("The E2E user has unexpected TOTP devices")
    TOTPDevice.objects.update_or_create(
        user=user,
        name=TOTP_DEVICE_NAME,
        defaults={
            "confirmed": True,
            "key": totp_key,
            "step": 30,
            "t0": 0,
            "digits": 6,
            "tolerance": 1,
            "drift": 0,
            "last_t": -1,
        },
    )

print(
    json.dumps(
        {
            "username": USERNAME,
            "organization": TEST_ORGANIZATION,
            "initializer_role": INITIALIZER_ROLE,
            "initializer_role_assigned": user.groups.filter(pk=initializer_role.pk).exists(),
            "debug_redirect": DEBUG_REDIRECT_URI,
            "created": created,
        },
        sort_keys=True,
    )
)
