#!/usr/bin/env bash
set -euo pipefail

apply="${ML_OFFBOARD_RECONCILE_APPLY:-0}"
base_dir="/opt/mission-leben-device-offboarding"
state_dir="/var/lib/mission-leben-device-offboarding"
processed_locks_file="${state_dir}/processed-device-locks"
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
else
    install -d -m 0700 "${state_dir}"
    touch "${processed_locks_file}"
    chmod 0600 "${processed_locks_file}"
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

while IFS=$'\t' read -r device_id lock_key; do
    [[ -n "${device_id}" ]] || continue
    [[ "${lock_key}" =~ ^[0-9a-f]{64}$ ]] || {
        echo "ML_OFFBOARD_STATUS=invalid-device-lock-key" >&2
        exit 1
    }
    if [[ "${apply}" == "1" ]] && grep -Fqx "${lock_key}" "${processed_locks_file}"; then
        continue
    fi
    bridge_result="$(docker exec "${bridge_container}" \
        mission-leben-bridge-lock-device --device-id "${device_id}" ${bridge_flag})"
    echo "ML_BRIDGE_DEVICE_LOCK=${bridge_result}"
    if [[ "${apply}" == "1" ]]; then
        printf '%s\n' "${lock_key}" >> "${processed_locks_file}"
    fi
done < <(
    python3 -c 'import json,sys; data=json.load(sys.stdin); print("\n".join(f"{item['\''device_id'\'']}\t{item['\''lock_key'\'']}" for item in data["disabled_device_locks"]))' \
        <<< "${reconcile_json}"
)

python3 -c \
    'import json,sys; data=json.load(sys.stdin); data.pop("inactive_subjects", None); data.pop("disabled_device_ids", None); data.pop("disabled_device_locks", None); print("ML_OFFBOARD_RECONCILE=" + json.dumps(data, separators=(",", ":"), sort_keys=True))' \
    <<< "${reconcile_json}"
