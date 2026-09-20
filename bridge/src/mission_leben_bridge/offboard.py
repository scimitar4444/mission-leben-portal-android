from __future__ import annotations

import argparse
import json

from .config import Settings
from .security import SecretBox
from .store import Store


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Remove personal communication data for an inactive Authentik subject."
    )
    parser.add_argument("--subject", required=True)
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()
    subject = args.subject.strip()
    if not subject or len(subject) > 200:
        parser.error("subject must contain between 1 and 200 characters")

    settings = Settings.from_env()
    store = Store(
        settings.database_path,
        settings.internal_hmac_secret,
        SecretBox(settings.data_key),
    )
    result = store.offboard_subject(subject, dry_run=args.dry_run)
    print(json.dumps(result, separators=(",", ":"), sort_keys=True))


if __name__ == "__main__":
    main()
