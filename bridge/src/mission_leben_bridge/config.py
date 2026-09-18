from __future__ import annotations

import base64
import hashlib
import json
import os
from dataclasses import dataclass
from pathlib import Path
from typing import Any


def _required(name: str, dev_mode: bool) -> str:
    value = os.getenv(name, "").strip()
    if value:
        return value
    if dev_mode:
        return f"development-only-{name.lower()}"
    raise RuntimeError(f"{name} must be configured")


@dataclass(frozen=True)
class Settings:
    listen_host: str
    listen_port: int
    database_path: Path
    authentik_userinfo_url: str
    authentik_agent_config_url: str
    internal_hmac_secret: bytes
    data_key: bytes
    firebase_project_id: str
    google_credentials_path: Path | None
    talk_targets: tuple[dict[str, Any], ...]
    nextcloud_talk_secret: bytes | None
    nextcloud_backend_url: str
    talk_recipients: dict[str, tuple[str, ...]]
    nextcloud_user_subjects: dict[str, str]
    dev_mode: bool

    @property
    def fcm_configured(self) -> bool:
        return bool(self.firebase_project_id and self.google_credentials_path)

    @classmethod
    def from_env(cls) -> "Settings":
        dev_mode = os.getenv("BRIDGE_DEV_MODE", "false").lower() in {"1", "true", "yes"}
        hmac_value = _required("BRIDGE_INTERNAL_HMAC_SECRET", dev_mode)
        if not dev_mode and len(hmac_value) < 32:
            raise RuntimeError("Bridge HMAC secret must contain at least 32 characters")

        data_key_value = os.getenv("BRIDGE_DATA_KEY", "").strip()
        if data_key_value:
            try:
                data_key = base64.urlsafe_b64decode(data_key_value + "=" * (-len(data_key_value) % 4))
            except ValueError as error:
                raise RuntimeError("BRIDGE_DATA_KEY must be base64url") from error
            if len(data_key) != 32:
                raise RuntimeError("BRIDGE_DATA_KEY must decode to exactly 32 bytes")
        elif dev_mode:
            data_key = hashlib.sha256(hmac_value.encode()).digest()
        else:
            raise RuntimeError("BRIDGE_DATA_KEY must be configured")

        raw_targets = os.getenv("BRIDGE_TALK_TARGETS_JSON", "[]")
        try:
            parsed_targets = json.loads(raw_targets)
        except json.JSONDecodeError as error:
            raise RuntimeError("BRIDGE_TALK_TARGETS_JSON is invalid JSON") from error
        if not isinstance(parsed_targets, list):
            raise RuntimeError("BRIDGE_TALK_TARGETS_JSON must contain a list")

        credentials = os.getenv("GOOGLE_APPLICATION_CREDENTIALS", "").strip()
        talk_secret_file = os.getenv("BRIDGE_NEXTCLOUD_TALK_SECRET_FILE", "").strip()
        talk_secret_value = os.getenv("BRIDGE_NEXTCLOUD_TALK_SECRET", "").strip()
        if talk_secret_file:
            talk_secret_value = Path(talk_secret_file).read_text(encoding="utf-8").strip()
        recipients_value = json.loads(os.getenv("BRIDGE_TALK_RECIPIENTS_JSON", "{}"))
        user_subjects_value = json.loads(os.getenv("BRIDGE_NEXTCLOUD_USER_SUBJECTS_JSON", "{}"))
        if not isinstance(recipients_value, dict) or not isinstance(user_subjects_value, dict):
            raise RuntimeError("Nextcloud mapping values must be JSON objects")
        return cls(
            listen_host=os.getenv("BRIDGE_LISTEN_HOST", "0.0.0.0"),
            listen_port=int(os.getenv("BRIDGE_LISTEN_PORT", "8080")),
            database_path=Path(os.getenv("BRIDGE_DATABASE_PATH", "/data/bridge.sqlite3")),
            authentik_userinfo_url=os.getenv(
                "BRIDGE_AUTHENTIK_USERINFO_URL",
                "https://id.mission-leben.de/application/o/userinfo/",
            ),
            authentik_agent_config_url=os.getenv(
                "BRIDGE_AUTHENTIK_AGENT_CONFIG_URL",
                "https://id.mission-leben.de/api/v3/endpoints/agents/connectors/agent_config/",
            ),
            internal_hmac_secret=hmac_value.encode(),
            data_key=data_key,
            firebase_project_id=os.getenv("BRIDGE_FIREBASE_PROJECT_ID", "").strip(),
            google_credentials_path=Path(credentials) if credentials else None,
            talk_targets=tuple(parsed_targets),
            nextcloud_talk_secret=talk_secret_value.encode() if talk_secret_value else None,
            nextcloud_backend_url=os.getenv("BRIDGE_NEXTCLOUD_BACKEND_URL", "").rstrip("/"),
            talk_recipients={
                str(room): tuple(str(subject) for subject in subjects)
                for room, subjects in recipients_value.items()
                if isinstance(subjects, list)
            },
            nextcloud_user_subjects={str(user): str(subject) for user, subject in user_subjects_value.items()},
            dev_mode=dev_mode,
        )
