#!/usr/bin/env bash
# Keep short-lived CLI/healthcheck metrics out of the live server's multiprocess
# directory. The unchanged, hash-checked upstream launcher is kept alongside us.
set -euo pipefail
vendor=/lifecycle/ak-vendor

case "${1:-}" in
    server|worker|allinone|debug)
        exec "${vendor}" "$@"
        ;;
esac

# Do not change TMPDIR: ak healthcheck reads authentik-mode from that location.
metric_dir="$(mktemp -d /tmp/ml-authentik-cli-metrics.XXXXXXXX)"
cleanup() {
    # Only our generated directory, regular metric files, no recursive deletion.
    case "${metric_dir}" in
        /tmp/ml-authentik-cli-metrics.????????) ;;
        *) return 1 ;;
    esac
    find "${metric_dir}" -maxdepth 1 -type f -name '*.db' -delete
    rmdir -- "${metric_dir}"
}
trap cleanup EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM
result=0
PROMETHEUS_MULTIPROC_DIR="${metric_dir}" "${vendor}" "$@" || result=$?
exit "${result}"
