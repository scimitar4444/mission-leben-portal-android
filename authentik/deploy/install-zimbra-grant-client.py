#!/usr/bin/env python3
"""Provision the restricted Zimbra SSH client on the Authentik host."""

from __future__ import annotations

import argparse
import base64
import hashlib
import ipaddress
import os
import subprocess
from pathlib import Path


BASE = Path("/opt/mission-leben-communication-sync")
KEY_DIR = BASE / "keys"
KEY = KEY_DIR / "zimbra-grant-sync"
KNOWN_HOSTS = KEY_DIR / "known_hosts"
ENV_FILE = BASE / "grant-sync.env"


def write_private(path: Path, data: str, mode: int) -> None:
    temporary = path.with_name(path.name + ".tmp")
    descriptor = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, mode)
    with os.fdopen(descriptor, "w", encoding="ascii") as handle:
        handle.write(data)
    os.replace(temporary, path)
    os.chmod(path, mode)


def install(host: str, expected_fingerprint: str) -> None:
    ipaddress.ip_address(host)
    KEY_DIR.mkdir(mode=0o700, parents=True, exist_ok=True)
    if not KEY.exists():
        subprocess.run(
            ["/usr/bin/ssh-keygen", "-q", "-t", "ed25519", "-N", "", "-f", str(KEY), "-C", "mission-leben-zimbra-grant-sync"],
            check=True,
            timeout=30,
        )
    os.chmod(KEY, 0o600)
    scanned = subprocess.run(
        ["/usr/bin/ssh-keyscan", "-T", "5", "-t", "ed25519", host],
        capture_output=True,
        text=True,
        timeout=10,
        check=True,
    ).stdout
    keys = {line for line in scanned.splitlines() if line.startswith(host + " ssh-ed25519 ")}
    if len(keys) != 1:
        raise RuntimeError("Zimbra host did not offer exactly one ED25519 host key")
    line = keys.pop()
    fingerprint = "SHA256:" + base64.b64encode(
        hashlib.sha256(base64.b64decode(line.split()[2], validate=True)).digest()
    ).decode("ascii").rstrip("=")
    if fingerprint != expected_fingerprint:
        raise RuntimeError("Zimbra host key fingerprint does not match the independently verified value")
    if KNOWN_HOSTS.exists() and KNOWN_HOSTS.read_text(encoding="ascii").strip() != line:
        raise RuntimeError("existing pinned Zimbra host key differs")
    write_private(KNOWN_HOSTS, line + "\n", 0o600)
    if ENV_FILE.exists() and ENV_FILE.read_text(encoding="ascii").strip() != f"ML_ZIMBRA_GRANT_SSH_HOST={host}":
        raise RuntimeError("existing Zimbra grant-sync host differs")
    write_private(ENV_FILE, f"ML_ZIMBRA_GRANT_SSH_HOST={host}\n", 0o600)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", required=True)
    parser.add_argument("--expected-host-fingerprint", required=True)
    args = parser.parse_args()
    install(args.host, args.expected_host_fingerprint)


if __name__ == "__main__":
    main()
