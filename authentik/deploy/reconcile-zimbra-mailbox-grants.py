#!/usr/bin/env python3
"""Restricted SSH command on the Zimbra host for app mailbox delegation.

The Authentik host sends only the currently eligible mailbox addresses. This
command is deliberately unable to grant a domain/global right or any right
other than account-scoped adminLoginAs to the fixed connector account.
"""

from __future__ import annotations

import fcntl
import json
import os
import re
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path
from uuid import UUID


CONNECTOR = "mobile-push-connector@mission-leben.de"
ZMPROV = "/opt/zimbra/bin/zmprov"
STATE_DIR = Path("/var/lib/mission-leben-zimbra-grant-sync")
LOCK_FILE = Path("/run/lock/mission-leben-zimbra-grant-sync.lock")
EMAIL_RE = re.compile(r"[a-z0-9][a-z0-9._%+-]{0,127}@[a-z0-9.-]+\.[a-z]{2,}")


class ReconcileError(Exception):
    pass


def zmprov(*arguments: str, input_text: str | None = None) -> str:
    result = subprocess.run(
        ["/usr/sbin/runuser", "-u", "zimbra", "--", ZMPROV, *arguments],
        capture_output=True,
        input=input_text,
        text=True,
        timeout=45,
        check=False,
    )
    if result.returncode:
        raise ReconcileError(f"Zimbra command {arguments[0] if arguments else 'batch'} failed: {result.stderr.strip()[:250]}")
    return result.stdout


def parse_desired(payload: object, allowed_domains: set[str]) -> tuple[str, set[str]]:
    if not isinstance(payload, dict) or payload.get("mode") not in {"prepare", "finalize"}:
        raise ReconcileError("invalid reconciliation mode")
    emails = payload.get("emails")
    if not isinstance(emails, list) or len(emails) > 10_000:
        raise ReconcileError("emails must be a bounded list")
    desired: set[str] = set()
    for value in emails:
        if not isinstance(value, str) or value != value.strip().lower() or not EMAIL_RE.fullmatch(value):
            raise ReconcileError("invalid mailbox address")
        if value.rsplit("@", 1)[1] not in allowed_domains or value == CONNECTOR:
            raise ReconcileError("mailbox domain or account is not allowed")
        desired.add(value)
    return payload["mode"], desired


def parse_grants(output: str) -> set[str]:
    current: set[str] = set()
    for line in output.splitlines():
        columns = line.split()
        if not columns or columns[-1] != "adminLoginAs":
            continue
        if len(columns) < 7 or columns[0] != "account" or columns[3] != "usr":
            raise ReconcileError("connector has a non-account adminLoginAs grant")
        try:
            account_id = str(UUID(columns[1]))
        except ValueError as error:
            raise ReconcileError("grant has an invalid account UUID") from error
        # zmprov gg truncates the displayed account name to 28 characters.
        # Only its stable target UUID is authoritative, never columns[2].
        current.add(account_id)
    return current


def current_accounts(account_ids: set[str]) -> dict[str, str]:
    if not account_ids:
        return {}
    if len(account_ids) > 10_000:
        raise ReconcileError("current grants exceed bounded account list")
    # One JVM/SOAP session for the entire read-only lookup, including on large
    # deployments. ga headers and zimbraId are not table-width truncated.
    commands = "".join(f"ga {account_id} zimbraId\n" for account_id in sorted(account_ids))
    output = zmprov(input_text=commands + "quit\n")
    result: dict[str, str] = {}
    name = ""
    for line in output.splitlines():
        if line.startswith("# name "):
            name = line[7:].strip().lower()
        elif line.startswith("zimbraId:"):
            account_id = line.partition(":")[2].strip()
            if account_id not in account_ids or account_id in result or not EMAIL_RE.fullmatch(name):
                raise ReconcileError("current account lookup returned invalid or duplicate data")
            result[account_id] = name
            name = ""
    if set(result) != account_ids or len(set(result.values())) != len(result):
        raise ReconcileError("current account lookup is incomplete or ambiguous")
    return result


def validate_account(email: str) -> None:
    output = zmprov("ga", email, "zimbraAccountStatus", "zimbraId")
    name = next((line[7:].strip().lower() for line in output.splitlines() if line.startswith("# name ")), "")
    status = next(
        (line.partition(":")[2].strip().lower() for line in output.splitlines() if line.startswith("zimbraAccountStatus:")),
        "",
    )
    if name != email or status != "active":
        raise ReconcileError("desired mailbox is missing, aliased or inactive")


def verify_right(email: str, expected: str) -> None:
    outcome = zmprov("ckr", "account", email, CONNECTOR, "adminLoginAs").splitlines()[0].strip()
    if outcome != expected:
        raise ReconcileError(f"mailbox right verification did not return {expected}")


def save_before(current: set[str]) -> None:
    STATE_DIR.mkdir(mode=0o700, parents=True, exist_ok=True)
    temporary = STATE_DIR / "last-before.json.tmp"
    destination = STATE_DIR / "last-before.json"
    flags = os.O_WRONLY | os.O_CREAT | os.O_TRUNC
    descriptor = os.open(temporary, flags, 0o600)
    with os.fdopen(descriptor, "w", encoding="utf-8") as handle:
        json.dump(
            {"at": datetime.now(timezone.utc).isoformat(), "grants": sorted(current)},
            handle,
            separators=(",", ":"),
        )
        handle.write("\n")
    os.replace(temporary, destination)


def reconcile(mode: str, desired: set[str]) -> dict[str, int | str]:
    current = current_accounts(parse_grants(zmprov("gg", "-g", "usr", CONNECTOR)))
    additions = sorted(desired - set(current.values()))
    removals = sorted(account_id for account_id, email in current.items() if email not in desired) if mode == "finalize" else []
    for email in additions:
        validate_account(email)
    if additions or removals:
        save_before(set(current.values()))
    for email in additions:
        zmprov("grr", "account", email, "usr", CONNECTOR, "adminLoginAs")
        verify_right(email, "ALLOWED")
    for account_id in removals:
        zmprov("rvr", "account", account_id, "usr", CONNECTOR, "adminLoginAs")
        verify_right(account_id, "DENIED")
    return {"mode": mode, "desired": len(desired), "added": len(additions), "removed": len(removals)}


def main() -> int:
    raw = sys.stdin.buffer.read(5_000_001)
    if len(raw) > 5_000_000:
        raise ReconcileError("input exceeds 5 MB")
    allowed_domains = set(os.environ.get("ML_ALLOWED_ZIMBRA_DOMAINS", "mission-leben.de").split(","))
    mode, desired = parse_desired(json.loads(raw), allowed_domains)
    with LOCK_FILE.open("a+") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        result = reconcile(mode, desired)
    print("ML_ZIMBRA_GRANT_SYNC_STATUS=" + json.dumps(result, separators=(",", ":")))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (ReconcileError, OSError, ValueError, subprocess.TimeoutExpired) as error:
        print(f"ML_ZIMBRA_GRANT_SYNC_ERROR={error}", file=sys.stderr)
        raise SystemExit(1)
