#!/usr/bin/env bash
set -euo pipefail

if [[ "${EUID}" -ne 0 ]]; then
    echo "Run as root." >&2
    exit 1
fi

source_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
install_dir="/opt/mission-leben-device-offboarding"

install -d -m 0750 "${install_dir}"
install -m 0640 \
    "${source_dir}/reconcile_inactive_personal_devices.py" \
    "${install_dir}/reconcile_inactive_personal_devices.py"
install -m 0750 \
    "${source_dir}/deploy/reconcile-inactive-devices.sh" \
    "${install_dir}/reconcile-inactive-devices.sh"
install -m 0644 \
    "${source_dir}/deploy/mission-leben-device-offboarding.service" \
    "/etc/systemd/system/mission-leben-device-offboarding.service"
install -m 0644 \
    "${source_dir}/deploy/mission-leben-device-offboarding.timer" \
    "/etc/systemd/system/mission-leben-device-offboarding.timer"

systemctl daemon-reload
systemctl enable --now mission-leben-device-offboarding.timer
