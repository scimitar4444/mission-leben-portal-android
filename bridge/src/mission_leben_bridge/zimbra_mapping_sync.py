from __future__ import annotations

import json
import sys
from pathlib import Path
from typing import Any

from .zimbra_waitset import ZimbraSoapClient
from .zimbra_worker import _password, _required_env


def build_account_map(
    assignments: list[dict[str, Any]],
    existing: dict[str, dict[str, str]],
    soap: ZimbraSoapClient,
) -> dict[str, dict[str, str]]:
    existing_by_email = {
        str(mapping.get("email", "")).strip().lower(): (str(account_id), mapping)
        for account_id, mapping in existing.items()
        if isinstance(mapping, dict) and str(mapping.get("email", "")).strip()
    }
    candidates: dict[str, dict[str, str]] = {}
    for value in assignments:
        if not isinstance(value, dict) or not bool(value.get("zimbra")):
            continue
        subject = str(value.get("subject", "")).strip()
        email = str(value.get("email", "")).strip().lower()
        if not subject or not email or "@" not in email:
            raise RuntimeError("every Zimbra assignment needs an Authentik subject and email")
        if email in candidates and candidates[email]["subject"] != subject:
            raise RuntimeError(f"duplicate Zimbra email assignment: {email}")
        candidates[email] = {"subject": subject, "email": email}

    result: dict[str, dict[str, str]] = {}
    unresolved = [email for email in sorted(candidates) if email not in existing_by_email]
    if unresolved:
        soap.authenticate()
    for email, mapping in sorted(candidates.items()):
        existing_value = existing_by_email.get(email)
        account_id = existing_value[0] if existing_value else soap.account_id(email)
        if account_id in result and result[account_id]["subject"] != mapping["subject"]:
            raise RuntimeError(f"duplicate Zimbra account id: {account_id}")
        result[account_id] = mapping
    return result


def main() -> None:
    payload = json.load(sys.stdin)
    assignments = payload.get("assignments", []) if isinstance(payload, dict) else []
    if not isinstance(assignments, list):
        raise RuntimeError("assignments must be a list")
    path = Path(_required_env("ZIMBRA_ACCOUNT_MAP_FILE"))
    existing: dict[str, dict[str, str]] = {}
    if path.is_file():
        value = json.loads(path.read_text(encoding="utf-8"))
        if isinstance(value, dict):
            existing = value
    admin_url = _required_env("ZIMBRA_ADMIN_SOAP_URL")
    account_map = build_account_map(
        assignments,
        existing,
        ZimbraSoapClient(
            admin_soap_url=admin_url,
            mail_soap_url=_required_env("ZIMBRA_MAIL_SOAP_URL"),
            admin_user=_required_env("ZIMBRA_ADMIN_USER"),
            admin_password=_password(),
        ),
    )
    print(json.dumps(account_map, ensure_ascii=False, separators=(",", ":"), sort_keys=True))


if __name__ == "__main__":
    main()
