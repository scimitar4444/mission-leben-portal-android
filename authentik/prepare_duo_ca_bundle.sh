#!/usr/bin/env bash
set -euo pipefail

authentik_dir="${AUTHENTIK_DIR:-/opt/authentik}"
server_container="${AUTHENTIK_SERVER_CONTAINER:-authentik-server-1}"
output_file="${DUO_CA_OUTPUT_FILE:-$authentik_dir/ca/duo-client-ca.pem}"

command -v docker >/dev/null
install -d -m 0755 "$(dirname "$output_file")"

server_image="$(docker inspect --format '{{.Image}}' "$server_container")"
duo_ca_path="$(
    docker run --rm --entrypoint python "$server_image" -c \
        'import duo_client.client as client; print(client.DEFAULT_CA_CERTS)'
)"

case "$duo_ca_path" in
    /ak-root/.venv/lib/python*/site-packages/duo_client/ca_certs.pem) ;;
    *)
        echo "Unexpected Duo CA path: $duo_ca_path" >&2
        exit 1
        ;;
esac

temporary_file="$(mktemp "$(dirname "$output_file")/.duo-client-ca.XXXXXX")"
trap 'rm -f "$temporary_file"' EXIT

docker run --rm --entrypoint sh "$server_image" -c \
    'cat "$(python -c '\''import duo_client.client as client; print(client.DEFAULT_CA_CERTS)'\'')" /etc/ssl/certs/ISRG_Root_X1.pem /etc/ssl/certs/ISRG_Root_X2.pem' \
    > "$temporary_file"

certificate_count="$(grep -c 'BEGIN CERTIFICATE' "$temporary_file")"
if (( certificate_count < 17 )); then
    echo "Generated CA bundle is incomplete ($certificate_count certificates)." >&2
    exit 1
fi

install -o root -g root -m 0644 "$temporary_file" "$output_file"
printf 'DUO_CLIENT_CA_PATH=%s\n' "$duo_ca_path"
printf 'CA bundle: %s (%s certificates)\n' "$output_file" "$certificate_count"
