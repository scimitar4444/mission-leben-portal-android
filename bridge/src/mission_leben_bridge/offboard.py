from __future__ import annotations

import argparse
import json
import logging

from .config import Settings
from .fcm import FcmSendError, FcmSender, NullFcmSender
from .security import SecretBox
from .store import Store


LOGGER = logging.getLogger("mission_leben_bridge.offboard")


def offboard_subject(
    store: Store,
    fcm: FcmSender | NullFcmSender,
    subject: str,
    *,
    dry_run: bool = False,
) -> dict[str, object]:
    registrations = store.registrations_for_offboarding(subject)
    sent = 0
    failed = 0
    if not dry_run and fcm.configured:
        for registration in registrations:
            try:
                fcm.send(
                    registration["installation_id"],
                    {"action": "refresh_security_state"},
                )
                sent += 1
            except FcmSendError:
                failed += 1
                LOGGER.warning("security refresh signal could not be delivered")
            except Exception:
                failed += 1
                LOGGER.exception("unexpected security refresh delivery failure")

    result = store.offboard_subject(subject, dry_run=dry_run)
    result.update(
        {
            "security_signal_configured": fcm.configured,
            "security_signal_targets": len(registrations),
            "security_signals_sent": sent,
            "security_signals_failed": failed,
        }
    )
    return result


def _fcm_sender(settings: Settings) -> FcmSender | NullFcmSender:
    if not settings.fcm_configured or settings.google_credentials_path is None:
        return NullFcmSender()
    try:
        return FcmSender(settings.firebase_project_id, settings.google_credentials_path)
    except Exception:
        LOGGER.exception("FCM configuration could not be loaded; offboarding continues without push")
        return NullFcmSender()


def main() -> None:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")
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
    result = offboard_subject(store, _fcm_sender(settings), subject, dry_run=args.dry_run)
    print(json.dumps(result, separators=(",", ":"), sort_keys=True))


if __name__ == "__main__":
    main()
