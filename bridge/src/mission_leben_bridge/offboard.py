from __future__ import annotations

import argparse
import json
import logging

from .config import Settings
from .ntfy import NtfyError, NtfyManager, NullNtfyManager
from .security import SecretBox
from .store import Store


LOGGER = logging.getLogger("mission_leben_bridge.offboard")


def offboard_subject(
    store: Store,
    ntfy: NtfyManager | NullNtfyManager,
    subject: str,
    *,
    dry_run: bool = False,
) -> dict[str, object]:
    registrations = store.registrations_for_offboarding(subject)
    sent = 0
    failed = 0
    if not dry_run and ntfy.configured:
        for registration in registrations:
            if registration.get("push_provider") != "ntfy":
                continue
            try:
                ntfy.send(
                    registration["ntfy_topic"],
                    registration["publish_token"],
                    {"action": "refresh_security_state"},
                )
                sent += 1
            except NtfyError:
                failed += 1
                LOGGER.warning("security refresh signal could not be delivered")
            except Exception:
                failed += 1
                LOGGER.exception("unexpected security refresh delivery failure")

    if not dry_run and ntfy.configured:
        for registration in registrations:
            if registration.get("push_provider") != "ntfy":
                continue
            try:
                ntfy.revoke(
                    registration.get("ntfy_reader_username", ""),
                    registration.get("ntfy_writer_username", ""),
                )
            except NtfyError:
                LOGGER.warning("ntfy device identities could not be revoked")

    result = store.offboard_subject(subject, dry_run=dry_run)
    result.update(
        {
            "security_signal_configured": ntfy.configured,
            "security_signal_targets": sum(
                1 for registration in registrations
                if registration.get("push_provider") == "ntfy"
            ),
            "security_signals_sent": sent,
            "security_signals_failed": failed,
        }
    )
    return result


def _ntfy_manager(settings: Settings) -> NtfyManager | NullNtfyManager:
    if not settings.ntfy_configured or settings.ntfy_auth_file is None:
        return NullNtfyManager()
    try:
        return NtfyManager(
            settings.ntfy_public_base_url,
            settings.ntfy_internal_base_url,
            settings.ntfy_auth_file,
            settings.ntfy_binary,
        )
    except Exception:
        LOGGER.exception("ntfy configuration could not be loaded; offboarding continues without push")
        return NullNtfyManager()


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
    result = offboard_subject(store, _ntfy_manager(settings), subject, dry_run=args.dry_run)
    print(json.dumps(result, separators=(",", ":"), sort_keys=True))


if __name__ == "__main__":
    main()
