#!/usr/bin/env python3
"""Install/remove one forced-command SSH key without touching other root keys."""

from __future__ import annotations

import argparse
import ipaddress
import os
from pathlib import Path


MARKER = "mission-leben-zimbra-grant-sync"
COMMAND = "/usr/local/sbin/mission-leben-reconcile-zimbra-grants"
AUTHORIZED_KEYS = Path("/root/.ssh/authorized_keys")


def update_authorized_keys(source_ip: str | None, public_key: str | None) -> None:
    existing = AUTHORIZED_KEYS.read_text(encoding="utf-8").splitlines() if AUTHORIZED_KEYS.exists() else []
    retained = [line for line in existing if not line.rstrip().endswith(" " + MARKER)]
    if source_ip is not None:
        ipaddress.ip_address(source_ip)
        if public_key is None or "\n" in public_key or not public_key.startswith("ssh-ed25519 "):
            raise ValueError("expected one ED25519 public key")
        parts = public_key.split()
        if len(parts) < 2:
            raise ValueError("invalid public key")
        retained.append(
            f'from="{source_ip}",restrict,command="{COMMAND}" '
            f"{parts[0]} {parts[1]} {MARKER}"
        )
    AUTHORIZED_KEYS.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    temporary = AUTHORIZED_KEYS.with_name("authorized_keys.mission-leben.tmp")
    descriptor = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(descriptor, "w", encoding="utf-8") as handle:
        handle.write("\n".join(retained).rstrip("\n") + "\n")
    os.replace(temporary, AUTHORIZED_KEYS)
    os.chmod(AUTHORIZED_KEYS, 0o600)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source-ip")
    parser.add_argument("--public-key-file", type=Path)
    parser.add_argument("--remove", action="store_true")
    args = parser.parse_args()
    if args.remove:
        if args.source_ip or args.public_key_file:
            parser.error("--remove cannot be combined with key options")
        update_authorized_keys(None, None)
        return
    if not args.source_ip or not args.public_key_file:
        parser.error("--source-ip and --public-key-file are required")
    if not Path(COMMAND).is_file():
        parser.error("forced command must be installed before the key")
    update_authorized_keys(args.source_ip, args.public_key_file.read_text(encoding="ascii").strip())


if __name__ == "__main__":
    main()
