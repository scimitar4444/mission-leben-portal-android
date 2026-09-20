#!/usr/bin/env bash
set -euo pipefail

apply="${ML_OFFBOARD_RECONCILE_APPLY:-0}"
base_dir="/opt/mission-leben-device-offboarding"
authentik_container="${ML_AUTHENTIK_CONTAINER:-authentik-server-1}"
bridge_container="${ML_BRIDGE_CONTAINER:-mission-leben-device-bridge}"

exec 9>"/run/lock/mission-leben-device-offboarding.lock"
if ! flock -n 9; then
    echo "ML_OFFBOARD_STATUS=already-running"
    exit 0
fi

authentik_output="$({
    docker exec -i \
        -e "ML_OFFBOARD_RECONCILE_APPLY=${apply}" \
        "${authentik_container}" ak shell \
        < "${base_dir}/reconcile_inactive_personal_devices.py"
} 2>&1)" || {
    echo "ML_OFFBOARD_STATUS=authentik-reconcile-failed" >&2
    exit 1
}

reconcile_json="$({
    printf '%s\n' "${authentik_output}" \
        | sed -n 's/^ML_OFFBOARD_RECONCILE=//p' \
        | tail -n 1
})"
if [[ -z "${reconcile_json}" ]]; then
    echo "ML_OFFBOARD_STATUS=authentik-result-missing" >&2
    exit 1
fi

bridge_flag=""
if [[ "${apply}" != "1" ]]; then
    bridge_flag="--dry-run"
fi

while IFS= read -r subject; do
    [[ -n "${subject}" ]] || continue
    bridge_result="$(docker exec "${bridge_container}" \
        mission-leben-bridge-offboard --subject "${subject}" ${bridge_flag})"
    echo "ML_BRIDGE_OFFBOARD=${bridge_result}"
done < <(
    python3 -c 'import json,sys; data=json.load(sys.stdin); print("\n".join(data["inactive_subjects"]))' \
        <<< "${reconcile_json}"
)

python3 -c \
    'import json,sys; data=json.load(sys.stdin); data.pop("inactive_subjects", None); print("ML_OFFBOARD_RECONCILE=" + json.dumps(data, separators=(",", ":"), sort_keys=True))' \
    <<< "${reconcile_json}"
