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

install -d -m 0750 "$deploy_dir/data" "$deploy_dir/backups"
chown 10001:10001 "$deploy_dir/data"

if [[ ! -f "$env_file" ]]; then
    umask 077
    internal_hmac_secret="$(python3 -c 'import secrets; print(secrets.token_urlsafe(48))')"
    data_key="$(python3 -c 'import base64,secrets; print(base64.urlsafe_b64encode(secrets.token_bytes(32)).rstrip(b"=").decode())')"
    printf '%s\n' \
        'BRIDGE_LISTEN_HOST=0.0.0.0' \
        'BRIDGE_LISTEN_PORT=8080' \
        'BRIDGE_DATABASE_PATH=/data/bridge.sqlite3' \
        'BRIDGE_AUTHENTIK_USERINFO_URL=https://id.mission-leben.de/application/o/userinfo/' \
        'BRIDGE_AUTHENTIK_AGENT_CONFIG_URL=https://id.mission-leben.de/api/v3/endpoints/agents/connectors/agent_config/' \
        "BRIDGE_INTERNAL_HMAC_SECRET=$internal_hmac_secret" \
        "BRIDGE_DATA_KEY=$data_key" \
        'BRIDGE_FIREBASE_PROJECT_ID=' \
        'GOOGLE_APPLICATION_CREDENTIALS=' \
        'BRIDGE_TALK_TARGETS_JSON=[]' \
        'BRIDGE_NEXTCLOUD_BACKEND_URL=' \
        'BRIDGE_TALK_RECIPIENTS_JSON={}' \
        'BRIDGE_NEXTCLOUD_USER_SUBJECTS_JSON={}' \
        > "$env_file"
    chmod 0600 "$env_file"
fi

docker compose --project-name mission-leben-device -f "$compose_file" up -d --build bridge

for attempt in {1..30}; do
    if curl --fail --silent --show-error http://127.0.0.1:8080/healthz >/dev/null; then
        exit 0
    fi
    sleep 1
done

docker compose --project-name mission-leben-device -f "$compose_file" ps
docker compose --project-name mission-leben-device -f "$compose_file" logs --tail=100 bridge
exit 1
