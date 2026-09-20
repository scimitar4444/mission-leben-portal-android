from __future__ import annotations

import base64
import hashlib
import json
import os
import re
from dataclasses import dataclass
from pathlib import Path
from typing import Any


DUO_INTEGRATION_KEY = re.compile(r"^[A-Za-z0-9]{20,64}$")
API_HOSTNAME = re.compile(
    r"^(?=.{1,253}$)(?:[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?\.)+"
    r"[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?$"
)


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
    authentik_device_status_url: str
    internal_hmac_secret: bytes
    data_key: bytes
    ntfy_public_base_url: str
    ntfy_internal_base_url: str
    ntfy_auth_file: Path | None
    ntfy_binary: Path
    talk_targets: tuple[dict[str, Any], ...]
    nextcloud_talk_secret: bytes | None
    nextcloud_backend_url: str
    nextcloud_announcements_secret: bytes | None
    nextcloud_announcements_url: str
    announcement_cache_ttl_seconds: int
    announcement_stale_ttl_seconds: int
    talk_recipients: dict[str, tuple[str, ...]]
    nextcloud_user_subjects: dict[str, str]
    duo_integration_key: str
    duo_secret_key: bytes | None
    duo_api_hostname: str
    duo_approval_timeout_seconds: int
    dev_mode: bool

    @property
    def ntfy_configured(self) -> bool:
        return bool(
            self.ntfy_public_base_url
            and self.ntfy_internal_base_url
            and self.ntfy_auth_file
        )

    @property
    def duo_configured(self) -> bool:
        return bool(self.duo_integration_key and self.duo_secret_key and self.duo_api_hostname)

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

        ntfy_public_url = os.getenv("BRIDGE_NTFY_PUBLIC_BASE_URL", "").strip().rstrip("/")
        ntfy_internal_url = os.getenv("BRIDGE_NTFY_INTERNAL_BASE_URL", "").strip().rstrip("/")
        ntfy_auth_file_value = os.getenv("BRIDGE_NTFY_AUTH_FILE", "").strip()
        ntfy_values = (ntfy_public_url, ntfy_internal_url, ntfy_auth_file_value)
        if any(ntfy_values) and not all(ntfy_values):
            raise RuntimeError("All BRIDGE_NTFY_* connection values must be configured together")
        talk_secret_file = os.getenv("BRIDGE_NEXTCLOUD_TALK_SECRET_FILE", "").strip()
        talk_secret_value = os.getenv("BRIDGE_NEXTCLOUD_TALK_SECRET", "").strip()
        if talk_secret_file:
            talk_secret_value = Path(talk_secret_file).read_text(encoding="utf-8").strip()
        announcements_secret_file = os.getenv(
            "BRIDGE_NEXTCLOUD_ANNOUNCEMENTS_SECRET_FILE", ""
        ).strip()
        announcements_secret_value = os.getenv(
            "BRIDGE_NEXTCLOUD_ANNOUNCEMENTS_SECRET", ""
        ).strip()
        if announcements_secret_file:
            announcements_secret_value = Path(announcements_secret_file).read_text(
                encoding="utf-8"
            ).strip()
        announcements_url = os.getenv(
            "BRIDGE_NEXTCLOUD_ANNOUNCEMENTS_URL", ""
        ).strip().rstrip("/")
        if bool(announcements_url) != bool(announcements_secret_value):
            raise RuntimeError(
                "BRIDGE_NEXTCLOUD_ANNOUNCEMENTS_URL and secret must be configured together"
            )
        if announcements_secret_value and len(announcements_secret_value) < 32:
            raise RuntimeError(
                "BRIDGE_NEXTCLOUD_ANNOUNCEMENTS_SECRET must contain at least 32 characters"
            )
        try:
            announcement_cache_ttl = int(
                os.getenv("BRIDGE_ANNOUNCEMENT_CACHE_TTL_SECONDS", "300")
            )
            announcement_stale_ttl = int(
                os.getenv("BRIDGE_ANNOUNCEMENT_STALE_TTL_SECONDS", "86400")
            )
        except ValueError as error:
            raise RuntimeError("Announcement cache TTL values must be integers") from error
        if not 30 <= announcement_cache_ttl <= 3600:
            raise RuntimeError("Announcement cache TTL must be between 30 and 3600 seconds")
        if not announcement_cache_ttl <= announcement_stale_ttl <= 604800:
            raise RuntimeError(
                "Announcement stale TTL must be at least the cache TTL and at most 604800 seconds"
            )
        recipients_value = json.loads(os.getenv("BRIDGE_TALK_RECIPIENTS_JSON", "{}"))
        user_subjects_value = json.loads(os.getenv("BRIDGE_NEXTCLOUD_USER_SUBJECTS_JSON", "{}"))
        if not isinstance(recipients_value, dict) or not isinstance(user_subjects_value, dict):
            raise RuntimeError("Nextcloud mapping values must be JSON objects")
        duo_integration_key = os.getenv("BRIDGE_DUO_INTEGRATION_KEY", "").strip()
        duo_secret_value = os.getenv("BRIDGE_DUO_SECRET_KEY", "").strip()
        duo_api_hostname = os.getenv("BRIDGE_DUO_API_HOSTNAME", "").strip().lower()
        duo_values = (duo_integration_key, duo_secret_value, duo_api_hostname)
        if any(duo_values) and not all(duo_values):
            raise RuntimeError("All BRIDGE_DUO_* values must be configured together")
        if duo_integration_key and not DUO_INTEGRATION_KEY.fullmatch(duo_integration_key):
            raise RuntimeError(
                "BRIDGE_DUO_INTEGRATION_KEY must contain 20 to 64 alphanumeric characters"
            )
        if duo_secret_value and len(duo_secret_value) < 32:
            raise RuntimeError("BRIDGE_DUO_SECRET_KEY must contain at least 32 characters")
        if duo_api_hostname and not API_HOSTNAME.fullmatch(duo_api_hostname):
            raise RuntimeError("BRIDGE_DUO_API_HOSTNAME must be a hostname without scheme or path")
        try:
            duo_timeout = int(os.getenv("BRIDGE_DUO_APPROVAL_TIMEOUT_SECONDS", "60"))
        except ValueError as error:
            raise RuntimeError("BRIDGE_DUO_APPROVAL_TIMEOUT_SECONDS must be an integer") from error
        if not 15 <= duo_timeout <= 90:
            raise RuntimeError("BRIDGE_DUO_APPROVAL_TIMEOUT_SECONDS must be between 15 and 90")
        return cls(
            listen_host=os.getenv("BRIDGE_LISTEN_HOST", "0.0.0.0"),
            listen_port=int(os.getenv("BRIDGE_LISTEN_PORT", "8080")),
            database_path=Path(os.getenv("BRIDGE_DATABASE_PATH", "/data/bridge.sqlite3")),
            authentik_userinfo_url=os.getenv(
                "BRIDGE_AUTHENTIK_USERINFO_URL",
                "https://id.mission-leben.de/application/o/userinfo/",
            ),
            authentik_device_status_url=os.getenv(
                "BRIDGE_AUTHENTIK_DEVICE_STATUS_URL",
                "https://geraete.mission-leben.de/api/v1/devices/status",
            ),
            internal_hmac_secret=hmac_value.encode(),
            data_key=data_key,
            ntfy_public_base_url=ntfy_public_url,
            ntfy_internal_base_url=ntfy_internal_url,
            ntfy_auth_file=Path(ntfy_auth_file_value) if ntfy_auth_file_value else None,
            ntfy_binary=Path(os.getenv("BRIDGE_NTFY_BINARY", "/usr/local/bin/ntfy")),
            talk_targets=tuple(parsed_targets),
            nextcloud_talk_secret=talk_secret_value.encode() if talk_secret_value else None,
            nextcloud_backend_url=os.getenv("BRIDGE_NEXTCLOUD_BACKEND_URL", "").rstrip("/"),
            nextcloud_announcements_secret=(
                announcements_secret_value.encode() if announcements_secret_value else None
            ),
            nextcloud_announcements_url=announcements_url,
            announcement_cache_ttl_seconds=announcement_cache_ttl,
            announcement_stale_ttl_seconds=announcement_stale_ttl,
            talk_recipients={
                str(room): tuple(str(subject) for subject in subjects)
                for room, subjects in recipients_value.items()
                if isinstance(subjects, list)
            },
            nextcloud_user_subjects={str(user): str(subject) for user, subject in user_subjects_value.items()},
            duo_integration_key=duo_integration_key,
            duo_secret_key=duo_secret_value.encode() if duo_secret_value else None,
            duo_api_hostname=duo_api_hostname,
            duo_approval_timeout_seconds=duo_timeout,
            dev_mode=dev_mode,
        )
