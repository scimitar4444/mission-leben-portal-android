#!/usr/bin/env bash
set -euo pipefail

base_dir="/opt/mission-leben-communication-sync"
bridge_dir="/opt/mission-leben-bridge"
authentik_container="${ML_AUTHENTIK_CONTAINER:-authentik-server-1}"
zimbra_container="${ML_ZIMBRA_CONTAINER:-mission-leben-zimbra-worker}"
map_file="${bridge_dir}/secrets/zimbra-account-map.json"
directory_file="${bridge_dir}/directory-data/communication-assignments.json"

exec 9>"/run/lock/mission-leben-communication-sync.lock"
if ! flock -n 9; then
    echo "ML_COMMUNICATION_SYNC_STATUS=already-running"
    exit 0
fi

subjects_json="$(docker exec -i "${ML_BRIDGE_CONTAINER:-mission-leben-device-bridge}" python - <<'PY'
import json, sqlite3
connection = sqlite3.connect("file:/data/bridge.sqlite3?mode=ro", uri=True)
rows = connection.execute(
    """
    SELECT DISTINCT r.subject
    FROM push_registrations r
    JOIN users u ON u.subject = r.subject
    WHERE r.push_enabled = 1 AND u.active = 1
    ORDER BY r.subject
    """
).fetchall()
print(json.dumps([row[0] for row in rows], separators=(",", ":")))
PY
)"

authentik_output="$({
    docker exec -i \
        -e "ML_COMMUNICATION_SUBJECTS_JSON=${subjects_json}" \
        "${authentik_container}" ak shell \
        < "${base_dir}/export_communication_assignments.py"
} 2>&1)" || {
    echo "ML_COMMUNICATION_SYNC_STATUS=authentik-export-failed" >&2
    exit 1
}

assignments_json="$(
    printf '%s\n' "${authentik_output}" \
        | sed -n 's/^ML_COMMUNICATION_ASSIGNMENTS=//p' \
        | tail -n 1
)"
if [[ -z "${assignments_json}" ]]; then
    echo "ML_COMMUNICATION_SYNC_STATUS=authentik-result-missing" >&2
    exit 1
fi

directory_candidate="$(mktemp)"
trap 'rm -f "${directory_candidate}"' EXIT
chmod 0600 "${directory_candidate}"
python3 -c \
    'import json,sys; value=json.load(sys.stdin); assert isinstance(value,dict) and isinstance(value.get("assignments"),list); print(json.dumps(value,separators=(",",":"),sort_keys=True))' \
    <<< "${assignments_json}" > "${directory_candidate}"
directory_status="unchanged"
if ! python3 - "${directory_file}" "${directory_candidate}" <<'PY'
import json, pathlib, sys
path = pathlib.Path(sys.argv[1])
old = json.loads(path.read_text(encoding="utf-8")) if path.is_file() else {}
new = json.loads(pathlib.Path(sys.argv[2]).read_text(encoding="utf-8"))
raise SystemExit(0 if old == new else 1)
PY
then
    timestamp="$(date -u +%Y%m%dT%H%M%SZ)"
    if [[ -f "${directory_file}" ]]; then
        install -m 0640 "${directory_file}" "${directory_file}.backup-${timestamp}"
        chown 0:10001 "${directory_file}.backup-${timestamp}"
    fi
    temporary_directory="$(mktemp "${bridge_dir}/directory-data/.communication-assignments.XXXXXX")"
    install -m 0640 "${directory_candidate}" "${temporary_directory}"
    chown 0:10001 "${temporary_directory}"
    mv -f "${temporary_directory}" "${directory_file}"
    directory_status="updated"
fi
rm -f "${directory_candidate}"
trap - EXIT

talk_sync_status="$(
    printf '%s' "${assignments_json}" \
        | docker exec -i "${ML_BRIDGE_CONTAINER:-mission-leben-device-bridge}" \
            python -m mission_leben_bridge.nextcloud_talk_bot_sync
)" || {
    echo "ML_COMMUNICATION_SYNC_STATUS=talk-room-sync-failed" >&2
    exit 1
}

candidate_map="$(
    printf '%s' "${assignments_json}" \
        | docker exec -i "${zimbra_container}" \
            python -m mission_leben_bridge.zimbra_mapping_sync
)" || {
    echo "ML_COMMUNICATION_SYNC_STATUS=zimbra-resolution-failed" >&2
    exit 1
}

python3 -c 'import json,sys; value=json.load(sys.stdin); assert isinstance(value,dict)' \
    <<< "${candidate_map}"

candidate_file="$(mktemp)"
trap 'rm -f "${candidate_file}"' EXIT
chmod 0600 "${candidate_file}"
printf '%s\n' "${candidate_map}" > "${candidate_file}"

if python3 - "${map_file}" "${candidate_file}" <<'PY'
import json, pathlib, sys
path = pathlib.Path(sys.argv[1])
old = json.loads(path.read_text(encoding="utf-8")) if path.is_file() else {}
new = json.loads(pathlib.Path(sys.argv[2]).read_text(encoding="utf-8"))
raise SystemExit(0 if old == new else 1)
PY
then
    count="$(python3 -c 'import json,sys; print(len(json.load(sys.stdin)))' <<< "${candidate_map}")"
    echo "ML_COMMUNICATION_SYNC_STATUS=unchanged accounts=${count} directory=${directory_status} ${talk_sync_status}"
    exit 0
fi

timestamp="$(date -u +%Y%m%dT%H%M%SZ)"
if [[ -f "${map_file}" ]]; then
    install -m 0640 "${map_file}" "${map_file}.backup-${timestamp}"
fi
temporary="$(mktemp "${bridge_dir}/secrets/.zimbra-account-map.XXXXXX")"
trap 'rm -f "${candidate_file}" "${temporary}"' EXIT
printf '%s\n' "${candidate_map}" > "${temporary}"
chmod 0640 "${temporary}"
mv -f "${temporary}" "${map_file}"
trap - EXIT
rm -f "${candidate_file}"

docker compose --project-name mission-leben-device \
    -f "${bridge_dir}/compose.device-pilot.yml" \
    --project-directory "${bridge_dir}" \
    up -d --no-deps --force-recreate zimbra-worker >/dev/null

count="$(python3 -c 'import json,sys; print(len(json.load(sys.stdin)))' <<< "${candidate_map}")"
echo "ML_COMMUNICATION_SYNC_STATUS=updated accounts=${count} directory=${directory_status} ${talk_sync_status}"
