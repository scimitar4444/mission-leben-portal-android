#!/usr/bin/env python3
"""Verify or replace exactly two bootstrap source files. Never execute them."""

from __future__ import annotations

import argparse
import ast
import hashlib
import inspect
import json
from pathlib import Path
import re
import subprocess
import sys

FILES = ("bootstrap_enrollment_portal.py", "bootstrap_e2e_debug.py")
REPO = Path(__file__).resolve().parents[1]
MANIFEST = REPO / "authentik/bootstrap-scripts.json"


def source_payload(repo: Path, manifest_path: Path) -> dict:
    manifest = json.loads(manifest_path.read_text())
    if manifest.get("schema") != 1 or set(manifest["files"]) != set(FILES):
        raise ValueError("Unexpected bootstrap manifest schema/files")
    sources = {}
    for name in FILES:
        raw = (repo / "authentik" / name).read_bytes()
        digest = hashlib.sha256(raw).hexdigest()
        if digest != manifest["files"][name]:
            raise ValueError(f"Unreviewed source hash: {name}; review/tests/manifest update required")
        source = raw.decode("utf-8")
        ast.parse(source, filename=name)
        if re.search(r"ML_DEVICE_INIT_|ENT_DEVICE_INITIALIZE_", source):
            raise ValueError(f"Legacy authority in {name}")
        if 'ML_AUTHENTIK_BOOTSTRAP_APPLY' not in source:
            raise ValueError(f"Execution guard missing in {name}")
        sources[name] = source
    return {"manifest": manifest, "sources": sources}


def remote_sync(payload, base_dir="/opt/authentik/admin-changes",
                backup_root="/opt/authentik/backups/bootstrap-source-sync"):
    """Standalone driver sent over SSH. Only file operations, no app imports."""
    import hashlib
    import json
    import os
    from pathlib import Path
    import shutil
    import stat
    import tempfile
    from datetime import datetime, timezone

    names = ("bootstrap_enrollment_portal.py", "bootstrap_e2e_debug.py")
    base = Path(base_dir)
    if base.is_symlink() or not base.is_dir():
        raise RuntimeError("Invalid bootstrap destination")
    if set(payload["sources"]) != set(names) or set(payload["manifest"]["files"]) != set(names):
        raise RuntimeError("Unexpected source file set")

    def digest(path):
        return hashlib.sha256(path.read_bytes()).hexdigest()

    current = {}
    for name in names:
        target = base / name
        info = target.lstat()
        if not stat.S_ISREG(info.st_mode) or info.st_nlink != 1:
            raise RuntimeError(f"Destination is not a single regular file: {name}")
        raw = payload["sources"][name].encode("utf-8")
        if hashlib.sha256(raw).hexdigest() != payload["manifest"]["files"][name]:
            raise RuntimeError(f"Transport hash mismatch: {name}")
        compile(raw, name, "exec")  # Syntax only. Never exec/eval the bootstrap.
        current[name] = {"sha256": digest(target), "mode": stat.S_IMODE(info.st_mode),
                         "uid": info.st_uid, "gid": info.st_gid, "mtime_ns": info.st_mtime_ns}
    result = {"before": current, "desired": payload["manifest"]["files"],
              "source_commit": payload.get("source_commit"), "executed_bootstraps": False}
    if not payload.get("apply"):
        result["status"] = "verified" if all(current[n]["sha256"] == result["desired"][n] for n in names) else "drift"
        return result
    if any(payload.get("expected", {}).get(n) != current[n]["sha256"] for n in names):
        raise RuntimeError("Remote source changed; inspect it and approve exact current hashes first")
    if all(current[n]["sha256"] == result["desired"][n] for n in names):
        return {**result, "status": "unchanged"}
    if os.geteuid() != 0 and str(base) == "/opt/authentik/admin-changes":
        raise RuntimeError("Production transfer requires root")

    root = Path(backup_root)
    if root.is_symlink():
        raise RuntimeError("Backup root must not be a symlink")
    root.mkdir(parents=True, exist_ok=True, mode=0o700)
    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ-")
    backup = Path(tempfile.mkdtemp(prefix=stamp, dir=root))
    staged = {}
    replaced = []
    try:
        # Copy both originals with their metadata before replacing either file.
        for name in names:
            original = backup / name
            shutil.copy2(base / name, original)
            os.chown(original, current[name]["uid"], current[name]["gid"])
            if digest(original) != current[name]["sha256"]:
                raise RuntimeError(f"Backup hash mismatch: {name}")
        record = {**result, "manifest": payload["manifest"]}
        (backup / "verification.json").write_text(json.dumps(record, indent=2) + "\n")
        os.chmod(backup / "verification.json", 0o600)
        for name in names:
            fd, temp = tempfile.mkstemp(prefix=".bootstrap-source-", dir=base)
            staged[name] = Path(temp)
            with os.fdopen(fd, "wb") as stream:
                stream.write(payload["sources"][name].encode("utf-8"))
                stream.flush()
                os.fsync(stream.fileno())
            shutil.copystat(base / name, temp)
            os.chown(temp, current[name]["uid"], current[name]["gid"])
            os.chmod(temp, current[name]["mode"])
        for name in names:
            # Recheck immediately before replacement; refuse stale overwrite.
            if digest(base / name) != current[name]["sha256"]:
                raise RuntimeError(f"Concurrent edit detected: {name}")
            os.replace(staged[name], base / name)
            replaced.append(name)
        for name in names:
            info = (base / name).stat()
            if (digest(base / name) != result["desired"][name]
                    or stat.S_IMODE(info.st_mode) != current[name]["mode"]
                    or (info.st_uid, info.st_gid) != (current[name]["uid"], current[name]["gid"])):
                raise RuntimeError(f"Post-transfer verification failed: {name}")
    except Exception:
        for name in replaced:
            if digest(base / name) == result["desired"][name]:
                shutil.copy2(backup / name, base / name)
                os.chown(base / name, current[name]["uid"], current[name]["gid"])
        raise
    finally:
        for temp in staged.values():
            temp.unlink(missing_ok=True)
    return {**result, "status": "updated", "backup": str(backup)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host", required=True, help="Reviewed SSH config alias")
    parser.add_argument("--apply", action="store_true", help="Copy sources only; never run them")
    parser.add_argument("--expect-portal-sha256")
    parser.add_argument("--expect-e2e-sha256")
    args = parser.parse_args()
    if not re.fullmatch(r"[a-zA-Z0-9][a-zA-Z0-9_.-]*", args.host):
        parser.error("Use a plain SSH config alias")
    expected = dict(zip(FILES, (args.expect_portal_sha256, args.expect_e2e_sha256)))
    if args.apply and any(not re.fullmatch(r"[0-9a-f]{64}", v or "") for v in expected.values()):
        parser.error("--apply requires both reviewed remote SHA-256 hashes")
    payload = source_payload(REPO, MANIFEST)
    # Do not transfer an uncommitted source candidate as the maintained version.
    commit = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=REPO, text=True).strip()
    for relative in ["authentik/" + name for name in FILES] + ["authentik/bootstrap-scripts.json", "scripts/sync_authentik_bootstraps.py"]:
        committed = subprocess.check_output(["git", "show", f"{commit}:{relative}"], cwd=REPO)
        if committed != (REPO / relative).read_bytes():
            raise RuntimeError(f"Uncommitted maintained source: {relative}")
    subprocess.run([sys.executable, "-m", "unittest", "discover", "-s", "authentik/tests",
                    "-p", "test_bootstrap_*.py"], cwd=REPO, check=True, capture_output=True)
    payload.update(apply=args.apply, expected=expected, source_commit=commit)
    driver = inspect.getsource(remote_sync) + "\nimport json\nprint(json.dumps(remote_sync(json.loads(" + repr(json.dumps(payload)) + "))))\n"
    completed = subprocess.run(["ssh", "-o", "BatchMode=yes", args.host, "python3 -"], input=driver, text=True, check=True, capture_output=True)
    result = json.loads(completed.stdout)
    print(json.dumps(result, indent=2))
    if not args.apply and result["status"] == "drift":
        raise SystemExit(2)


if __name__ == "__main__":
    main()
