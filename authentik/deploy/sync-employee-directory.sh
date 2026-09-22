#!/usr/bin/env bash
set -euo pipefail
exec 9>/run/lock/mission-leben-employee-directory.lock
flock -n 9 || exit 0
directory=/opt/mission-leben-bridge/directory-data
candidate="$(mktemp "${directory}/.employee-directory.XXXXXX")"
trap 'rm -f "${candidate}"' EXIT
chmod 0600 "${candidate}"
# Keep the full Authentik result off stdout/journald; only the bounded projection
# is written to the root-owned, bridge-readable volume. No historical copies.
docker exec -i "${ML_AUTHENTIK_CONTAINER:-authentik-server-1}" ak shell \
    -c 'import sys; exec(compile(sys.stdin.read(), "employee-directory", "exec"), {"__name__": "__main__"})' \
    < /opt/mission-leben-employee-directory/export_employee_directory.py 2>/dev/null \
    | sed -n 's/^ML_EMPLOYEE_DIRECTORY=//p' > "${candidate}"
python3 - "${candidate}" <<'PY'
import json, sys, time
with open(sys.argv[1], encoding="utf-8") as handle:
    data = json.load(handle)
assert data["version"] == 1 and isinstance(data["entries"], list)
assert 0 <= time.time() - data["generated_at"] < 60
assert all(isinstance(data[k], dict) for k in ("facilities", "audience", "devices"))
print("Employee directory refreshed: %d contacts, %d facilities" % (len(data["entries"]), len(data["facilities"])))
PY
chown 0:10001 "${candidate}"
chmod 0640 "${candidate}"
mv -f "${candidate}" "${directory}/employee-directory.json"
