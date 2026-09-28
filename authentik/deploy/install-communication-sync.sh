#!/usr/bin/env bash
set -euo pipefail

if [[ "${EUID}" -ne 0 ]]; then
    echo "Run as root." >&2
    exit 1
fi

source_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
install_dir="/opt/mission-leben-communication-sync"

install -d -m 0750 "${install_dir}"
install -d -m 0700 "${install_dir}/keys"
install -d -m 0750 /opt/mission-leben-bridge/directory-data
chown 0:10001 /opt/mission-leben-bridge/directory-data
install -m 0640 \
    "${source_dir}/export_communication_assignments.py" \
    "${install_dir}/export_communication_assignments.py"
install -m 0750 \
    "${source_dir}/deploy/sync-communication-assignments.sh" \
    "${install_dir}/sync-communication-assignments.sh"
install -m 0750 \
    "${source_dir}/deploy/install-zimbra-grant-client.py" \
    "${install_dir}/install-zimbra-grant-client.py"
if [[ ! -r "${install_dir}/grant-sync.env" || ! -r "${install_dir}/keys/zimbra-grant-sync" || ! -r "${install_dir}/keys/known_hosts" ]]; then
    echo "Provision Zimbra restricted grant-sync SSH before enabling the timer." >&2
    exit 1
fi
install -m 0644 \
    "${source_dir}/deploy/mission-leben-communication-sync.service" \
    "/etc/systemd/system/mission-leben-communication-sync.service"
install -m 0644 \
    "${source_dir}/deploy/mission-leben-communication-sync.timer" \
    "/etc/systemd/system/mission-leben-communication-sync.timer"

systemctl daemon-reload
systemctl enable --now mission-leben-communication-sync.timer
