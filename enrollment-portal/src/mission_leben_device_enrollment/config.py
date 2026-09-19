from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path
from urllib.parse import urlsplit
from uuid import UUID
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError


DEFAULT_APK_DOWNLOAD_URL = (
    "https://github.com/scimitar4444/mission-leben-portal-android/"
    "releases/latest/download/mission-leben-zentral.apk"
)


def _required(name: str) -> str:
    value = os.environ.get(name, "").strip()
    if not value:
        raise RuntimeError(f"Missing required environment variable: {name}")
    return value


def _secret_file(name: str) -> str:
    path = Path(_required(name))
    value = path.read_text(encoding="utf-8").strip()
    if len(value) < 32:
        raise RuntimeError(f"Secret in {path} is too short")
    return value


@dataclass(frozen=True)
class Settings:
    authentik_base_url: str
    authentik_api_token: str
    agent_connector_uuid: str
    public_origin: str
    proxy_app_slug: str
    csrf_secret: str
    app_approval_stage_uuid: str | None = None
    role_it_group: str = "ML_DEVICE_INIT_IT"
    role_central_group: str = "ML_DEVICE_INIT_ZENTRALE"
    role_el_group: str = "ML_DEVICE_INIT_EL"
    role_pdl_group: str = "ML_DEVICE_INIT_PDL"
    organization_group_prefix: str = "ORG_"
    token_ttl_seconds: int = 600
    display_timezone: str = "Europe/Berlin"
    apk_download_url: str = DEFAULT_APK_DOWNLOAD_URL
    android_cert_sha256_fingerprints: tuple[str, ...] = ()

    @classmethod
    def from_env(cls) -> "Settings":
        settings = cls(
            authentik_base_url=_required("ML_ENROLL_AUTHENTIK_BASE_URL").rstrip("/"),
            authentik_api_token=_secret_file("ML_ENROLL_AUTHENTIK_TOKEN_FILE"),
            agent_connector_uuid=_required("ML_ENROLL_AGENT_CONNECTOR_UUID"),
            public_origin=_required("ML_ENROLL_PUBLIC_ORIGIN").rstrip("/"),
            proxy_app_slug=os.environ.get(
                "ML_ENROLL_PROXY_APP_SLUG", "mission-leben-device-init"
            ).strip(),
            csrf_secret=_secret_file("ML_ENROLL_CSRF_SECRET_FILE"),
            app_approval_stage_uuid=(
                os.environ.get("ML_ENROLL_APP_APPROVAL_STAGE_UUID", "").strip() or None
            ),
            role_it_group=os.environ.get("ML_ENROLL_ROLE_IT_GROUP", "ML_DEVICE_INIT_IT").strip(),
            role_central_group=os.environ.get(
                "ML_ENROLL_ROLE_CENTRAL_GROUP", "ML_DEVICE_INIT_ZENTRALE"
            ).strip(),
            role_el_group=os.environ.get("ML_ENROLL_ROLE_EL_GROUP", "ML_DEVICE_INIT_EL").strip(),
            role_pdl_group=os.environ.get("ML_ENROLL_ROLE_PDL_GROUP", "ML_DEVICE_INIT_PDL").strip(),
            organization_group_prefix=os.environ.get("ML_ENROLL_ORG_PREFIX", "ORG_").strip(),
            token_ttl_seconds=int(os.environ.get("ML_ENROLL_TOKEN_TTL_SECONDS", "600")),
            display_timezone=os.environ.get("ML_ENROLL_DISPLAY_TIMEZONE", "Europe/Berlin").strip(),
            apk_download_url=os.environ.get(
                "ML_ENROLL_APK_DOWNLOAD_URL", DEFAULT_APK_DOWNLOAD_URL
            ).strip(),
            android_cert_sha256_fingerprints=tuple(
                fingerprint.strip().upper()
                for fingerprint in os.environ.get(
                    "ML_ENROLL_ANDROID_CERT_SHA256_FINGERPRINTS", ""
                ).split(",")
                if fingerprint.strip()
            ),
        )
        settings.validate()
        return settings

    def validate(self) -> None:
        for label, value in (
            ("ML_ENROLL_AUTHENTIK_BASE_URL", self.authentik_base_url),
            ("ML_ENROLL_PUBLIC_ORIGIN", self.public_origin),
            ("ML_ENROLL_APK_DOWNLOAD_URL", self.apk_download_url),
        ):
            parsed = urlsplit(value)
            if (
                parsed.scheme != "https"
                or not parsed.hostname
                or parsed.username
                or parsed.password
            ):
                raise RuntimeError(f"{label} must be a credential-free HTTPS URL")
            if label != "ML_ENROLL_APK_DOWNLOAD_URL" and (
                parsed.path not in {"", "/"} or parsed.query or parsed.fragment
            ):
                raise RuntimeError(f"{label} must be an HTTPS origin without a path")
            if label == "ML_ENROLL_APK_DOWNLOAD_URL" and (parsed.query or parsed.fragment):
                raise RuntimeError(f"{label} must not contain a query or fragment")
        try:
            UUID(self.agent_connector_uuid)
        except ValueError as error:
            raise RuntimeError("ML_ENROLL_AGENT_CONNECTOR_UUID must be a UUID") from error
        if self.app_approval_stage_uuid:
            try:
                UUID(self.app_approval_stage_uuid)
            except ValueError as error:
                raise RuntimeError("ML_ENROLL_APP_APPROVAL_STAGE_UUID must be a UUID") from error
        if not 120 <= self.token_ttl_seconds <= 600:
            raise RuntimeError("ML_ENROLL_TOKEN_TTL_SECONDS must be between 120 and 600")
        for fingerprint in self.android_cert_sha256_fingerprints:
            compact = fingerprint.replace(":", "")
            if len(compact) != 64 or any(
                character not in "0123456789ABCDEF" for character in compact
            ):
                raise RuntimeError(
                    "ML_ENROLL_ANDROID_CERT_SHA256_FINGERPRINTS must contain SHA-256 fingerprints"
                )
        try:
            ZoneInfo(self.display_timezone)
        except ZoneInfoNotFoundError as error:
            raise RuntimeError("ML_ENROLL_DISPLAY_TIMEZONE is invalid") from error
        role_groups = {
            self.role_it_group,
            self.role_central_group,
            self.role_el_group,
            self.role_pdl_group,
        }
        if "" in role_groups or len(role_groups) != 4:
            raise RuntimeError("Enrollment role group names must be non-empty and unique")
