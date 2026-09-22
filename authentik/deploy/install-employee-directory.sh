#!/usr/bin/env bash
set -euo pipefail
[[ "${EUID}" -eq 0 ]] || { echo "Run as root." >&2; exit 1; }
source_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
install_dir=/opt/mission-leben-employee-directory
install -d -m 0750 "${install_dir}"
install -d -m 0750 /opt/mission-leben-bridge/directory-data
chown 0:10001 /opt/mission-leben-bridge/directory-data
install -m 0640 "${source_dir}/export_employee_directory.py" "${install_dir}/export_employee_directory.py"
install -m 0750 "${source_dir}/deploy/sync-employee-directory.sh" "${install_dir}/sync-employee-directory.sh"
install -m 0644 "${source_dir}/deploy/mission-leben-employee-directory.service" /etc/systemd/system/mission-leben-employee-directory.service
install -m 0644 "${source_dir}/deploy/mission-leben-employee-directory.timer" /etc/systemd/system/mission-leben-employee-directory.timer
systemctl daemon-reload
systemctl start mission-leben-employee-directory.service
systemctl enable --now mission-leben-employee-directory.timer
