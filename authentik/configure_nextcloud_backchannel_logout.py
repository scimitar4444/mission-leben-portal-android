"""Configure Nextcloud user_oidc back-channel logout in Authentik.

Run inside the Authentik server container with ``ak shell`` after a PostgreSQL
backup. The script is read-only unless ``ML_NEXTCLOUD_LOGOUT_APPLY=1`` is set.
"""

from __future__ import annotations

import json
import os

from authentik.providers.oauth2.models import OAuth2Provider


PROVIDER_NAME = "Provider for Nextcloud Mission Leben"
CLIENT_ID = "nextcloud_mission_leben"
LOGOUT_URI = "https://nextcloud.mission-leben.de/apps/user_oidc/backchannel-logout/4"
APPLY = os.getenv("ML_NEXTCLOUD_LOGOUT_APPLY", "").strip() == "1"

matches = list(OAuth2Provider.objects.filter(name=PROVIDER_NAME, client_id=CLIENT_ID))
if len(matches) != 1:
    raise RuntimeError("The production Nextcloud OAuth2 provider is not unique")

provider = matches[0]
if str(provider.logout_method) != "backchannel":
    raise RuntimeError("The production Nextcloud provider is not configured for back-channel logout")

changed = provider.logout_uri != LOGOUT_URI
if APPLY and changed:
    provider.logout_uri = LOGOUT_URI
    provider.save(update_fields=["logout_uri"])

print(
    "ML_NEXTCLOUD_LOGOUT="
    + json.dumps(
        {
            "mode": "apply" if APPLY else "dry-run",
            "provider": PROVIDER_NAME,
            "client_id": CLIENT_ID,
            "logout_method": str(provider.logout_method),
            "logout_uri": LOGOUT_URI,
            "changed": changed,
        },
        separators=(",", ":"),
        sort_keys=True,
    )
)
