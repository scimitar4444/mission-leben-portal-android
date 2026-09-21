#!/usr/bin/env bash
set -euo pipefail

if [[ "${EUID:-$(id -u)}" -ne 0 ]]; then
    echo "Run this installer as root" >&2
    exit 1
fi

deploy_dir="${ML_BRIDGE_DEPLOY_DIR:-/opt/mission-leben-bridge}"
compose_file="$deploy_dir/compose.device-pilot.yml"
unit_dir="/etc/systemd/system"

required_files=(
    "$compose_file"
    "$deploy_dir/deploy/watchdog.sh"
    "$deploy_dir/deploy/mission-leben-bridge.service"
    "$deploy_dir/deploy/mission-leben-bridge-watchdog.service"
    "$deploy_dir/deploy/mission-leben-bridge-watchdog.timer"
    "$deploy_dir/deploy/mission-leben-bridge-preflight.service"
    "$deploy_dir/deploy/mission-leben-bridge-preflight.timer"
)
for path in "${required_files[@]}"; do
    if [[ ! -f "$path" ]]; then
        echo "Missing required file: $path" >&2
        exit 1
    fi
done

chmod 0750 "$deploy_dir/deploy/watchdog.sh"
docker compose --project-name mission-leben-device --file "$compose_file" config --quiet
docker compose \
    --project-name mission-leben-device \
    --file "$compose_file" \
    --profile tools \
    run --rm preflight

for unit in \
    mission-leben-bridge.service \
    mission-leben-bridge-watchdog.service \
    mission-leben-bridge-watchdog.timer \
    mission-leben-bridge-preflight.service \
    mission-leben-bridge-preflight.timer
do
    install -m 0644 "$deploy_dir/deploy/$unit" "$unit_dir/$unit"
done

systemctl daemon-reload
systemctl enable --now mission-leben-bridge.service
systemctl enable --now mission-leben-bridge-watchdog.timer
systemctl enable --now mission-leben-bridge-preflight.timer
systemctl start mission-leben-bridge-watchdog.service

systemctl is-active --quiet mission-leben-bridge.service
systemctl is-active --quiet mission-leben-bridge-watchdog.timer
systemctl is-active --quiet mission-leben-bridge-preflight.timer
