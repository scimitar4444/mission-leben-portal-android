#!/usr/bin/env bash
set -euo pipefail

deploy_dir="${ML_BRIDGE_DEPLOY_DIR:-/opt/mission-leben-bridge}"
database="$deploy_dir/data/bridge.sqlite3"
backup_dir="$deploy_dir/backups"

if [[ ! -f "$database" ]]; then
    echo "Bridge database does not exist: $database" >&2
    exit 1
fi

umask 077
install -d -m 0700 "$backup_dir"
timestamp="$(date -u +%Y%m%dT%H%M%SZ)"
temporary="$(mktemp "$backup_dir/.bridge-$timestamp.XXXXXX.sqlite3")"
target="$backup_dir/bridge-$timestamp.sqlite3.gz"

cleanup() {
    rm -f -- "$temporary" "$temporary.gz"
}
trap cleanup EXIT

python3 - "$database" "$temporary" <<'PY'
import sqlite3
import sys

source_path, target_path = sys.argv[1:]
with sqlite3.connect(f"file:{source_path}?mode=ro", uri=True) as source:
    with sqlite3.connect(target_path) as target:
        source.backup(target)
        result = target.execute("PRAGMA integrity_check").fetchone()
        if result != ("ok",):
            raise RuntimeError(f"backup integrity check failed: {result!r}")
PY

gzip -9 "$temporary"
mv -- "$temporary.gz" "$target"
find "$backup_dir" -maxdepth 1 -type f -name 'bridge-*.sqlite3.gz' -mtime +30 -delete
trap - EXIT
