#!/usr/bin/env bash
set -euo pipefail

deploy_dir="${ML_BRIDGE_DEPLOY_DIR:-/opt/mission-leben-bridge}"
compose_file="$deploy_dir/compose.device-pilot.yml"
env_file="$deploy_dir/.env"

if [[ ! -f "$compose_file" ]]; then
    echo "Missing $compose_file" >&2
    exit 1
fi

command -v docker >/dev/null
command -v python3 >/dev/null

install -d -m 0750 "$deploy_dir/data" "$deploy_dir/backups" "$deploy_dir/secrets" "$deploy_dir/ntfy-data"
chown 10001:10001 "$deploy_dir/data" "$deploy_dir/ntfy-data"

if [[ ! -f "$env_file" ]]; then
    umask 077
    internal_hmac_secret="$(python3 -c 'import secrets; print(secrets.token_urlsafe(48))')"
    data_key="$(python3 -c 'import base64,secrets; print(base64.urlsafe_b64encode(secrets.token_bytes(32)).rstrip(b"=").decode())')"
    duo_integration_key="$(python3 -c 'import secrets; print(secrets.token_hex(10).upper())')"
    duo_secret_key="$(python3 -c 'import secrets; print(secrets.token_urlsafe(48))')"
    printf '%s\n' \
        'BRIDGE_LISTEN_HOST=0.0.0.0' \
        'BRIDGE_LISTEN_PORT=8080' \
        'BRIDGE_DATABASE_PATH=/data/bridge.sqlite3' \
        'BRIDGE_AUTHENTIK_USERINFO_URL=https://id.mission-leben.de/application/o/userinfo/' \
        'BRIDGE_AUTHENTIK_DEVICE_STATUS_URL=https://geraete.mission-leben.de/api/v1/devices/status' \
        "BRIDGE_INTERNAL_HMAC_SECRET=$internal_hmac_secret" \
        "BRIDGE_DATA_KEY=$data_key" \
        "BRIDGE_DUO_INTEGRATION_KEY=$duo_integration_key" \
        "BRIDGE_DUO_SECRET_KEY=$duo_secret_key" \
        'BRIDGE_DUO_API_HOSTNAME=id.mission-leben.de' \
        'BRIDGE_DUO_APPROVAL_TIMEOUT_SECONDS=60' \
        'BRIDGE_NTFY_PUBLIC_BASE_URL=https://push.mission-leben.de' \
        'BRIDGE_NTFY_INTERNAL_BASE_URL=http://ntfy:2586' \
        'BRIDGE_NTFY_AUTH_FILE=/ntfy/user.db' \
        'BRIDGE_NTFY_BINARY=/usr/local/bin/ntfy' \
        'BRIDGE_TALK_TARGETS_JSON=[]' \
        'BRIDGE_NEXTCLOUD_BACKEND_URL=' \
        'BRIDGE_TALK_RECIPIENTS_JSON={}' \
        'BRIDGE_NEXTCLOUD_USER_SUBJECTS_JSON={}' \
        'BRIDGE_NEXTCLOUD_ANNOUNCEMENTS_URL=' \
        'BRIDGE_NEXTCLOUD_ANNOUNCEMENTS_SECRET_FILE=/run/secrets/nextcloud-announcements-secret' \
        'BRIDGE_ANNOUNCEMENT_CACHE_TTL_SECONDS=300' \
        'BRIDGE_ANNOUNCEMENT_STALE_TTL_SECONDS=86400' \
        > "$env_file"
    chmod 0600 "$env_file"
fi

ensure_env_value() {
    local name="$1"
    local value="$2"
    if ! grep -qE "^${name}=" "$env_file"; then
        printf '%s=%s\n' "$name" "$value" >> "$env_file"
    fi
}

ensure_env_value BRIDGE_NTFY_PUBLIC_BASE_URL https://push.mission-leben.de
ensure_env_value BRIDGE_NTFY_INTERNAL_BASE_URL http://ntfy:2586
ensure_env_value BRIDGE_NTFY_AUTH_FILE /ntfy/user.db
ensure_env_value BRIDGE_NTFY_BINARY /usr/local/bin/ntfy
chmod 0600 "$env_file"

docker compose --project-name mission-leben-device -f "$compose_file" up -d --build ntfy bridge

for attempt in {1..30}; do
    if curl --fail --silent --show-error http://127.0.0.1:8080/healthz >/dev/null; then
        exit 0
    fi
    sleep 1
done

docker compose --project-name mission-leben-device -f "$compose_file" ps
docker compose --project-name mission-leben-device -f "$compose_file" logs --tail=100 ntfy bridge
exit 1
