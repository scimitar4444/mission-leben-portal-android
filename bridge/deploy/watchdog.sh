#!/usr/bin/env bash
set -euo pipefail

deploy_dir="${ML_BRIDGE_DEPLOY_DIR:-/opt/mission-leben-bridge}"
compose_file="${ML_BRIDGE_COMPOSE_FILE:-$deploy_dir/compose.device-pilot.yml}"
state_dir="${ML_BRIDGE_WATCHDOG_STATE_DIR:-/run/mission-leben-bridge-watchdog}"
docker_bin="${ML_BRIDGE_DOCKER_BIN:-/usr/bin/docker}"
cooldown_seconds="${ML_BRIDGE_WATCHDOG_COOLDOWN_SECONDS:-600}"
wait_seconds="${ML_BRIDGE_WATCHDOG_WAIT_SECONDS:-90}"

case "$cooldown_seconds:$wait_seconds" in
    *[!0-9:]*|:*|*:) echo "Invalid watchdog timing configuration" >&2; exit 2 ;;
esac

if [[ ! -f "$compose_file" ]]; then
    echo "Missing Compose file: $compose_file" >&2
    exit 2
fi
if [[ ! -x "$docker_bin" ]]; then
    echo "Docker executable is unavailable: $docker_bin" >&2
    exit 2
fi

install -d -m 0750 "$state_dir"
exec 9>"$state_dir/watchdog.lock"
flock -n 9 || exit 0

services=(ntfy bridge zimbra-worker)
containers=(mission-leben-ntfy mission-leben-device-bridge mission-leben-zimbra-worker)
failed=0

compose() {
    "$docker_bin" compose \
        --project-name mission-leben-device \
        --file "$compose_file" \
        "$@"
}

container_state() {
    "$docker_bin" inspect \
        --format '{{.State.Status}}|{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' \
        "$1" 2>/dev/null || printf '%s\n' 'missing|missing'
}

wait_until_healthy() {
    local container="$1"
    local deadline=$((SECONDS + wait_seconds))
    while (( SECONDS <= deadline )); do
        if [[ "$(container_state "$container")" == "running|healthy" ]]; then
            return 0
        fi
        if (( wait_seconds == 0 )); then
            break
        fi
        sleep 2
    done
    return 1
}

recover_service() {
    local service="$1"
    local container="$2"
    local state="$3"
    local now last_recovery=0 stamp="$state_dir/$service.last-recovery"
    now="$(date +%s)"
    if [[ -f "$stamp" ]]; then
        last_recovery="$(<"$stamp")"
    fi
    if [[ ! "$last_recovery" =~ ^[0-9]+$ ]]; then
        last_recovery=0
    fi
    if (( now - last_recovery < cooldown_seconds )); then
        echo "$service is $state; automatic recovery is cooling down" >&2
        return 1
    fi
    printf '%s\n' "$now" > "$stamp"

    case "$state" in
        missing\|missing|exited\|*|dead\|*|created\|*)
            echo "$service is $state; starting it through Compose" >&2
            compose up -d --no-deps "$service"
            ;;
        *)
            echo "$service is $state; restarting it through Compose" >&2
            compose restart "$service"
            ;;
    esac

    if ! wait_until_healthy "$container"; then
        echo "$service did not become healthy after automatic recovery" >&2
        return 1
    fi
    echo "$service recovered and is healthy"
}

for index in "${!services[@]}"; do
    service="${services[$index]}"
    container="${containers[$index]}"
    state="$(container_state "$container")"
    case "$state" in
        running\|healthy)
            ;;
        running\|starting)
            echo "$service health check is still starting" >&2
            failed=1
            ;;
        running\|none)
            echo "$service has no Docker health check" >&2
            failed=1
            ;;
        *)
            if ! recover_service "$service" "$container" "$state"; then
                failed=1
            fi
            ;;
    esac
done

exit "$failed"
