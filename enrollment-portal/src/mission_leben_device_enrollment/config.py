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

DEFAULT_ROLE_IT_GROUPS = ("BR_IT_MANAGEMENT",)
DEFAULT_ROLE_CENTRAL_GROUPS = (
    "BR_GESCHAEFTSBEREICHSLEITUNG",
    "BR_GESCHAEFTSEINHEITSLEITUNG",
    "BR_ABTEILUNGSLEITUNG",
)
DEFAULT_ROLE_EL_GROUPS = ("BR_EINRICHTUNGSLEITUNG",)
DEFAULT_ROLE_PDL_GROUPS = ("BR_PFLEGEDIENSTLEITUNG",)


def _group_names(name: str, defaults: tuple[str, ...]) -> tuple[str, ...]:
    raw = os.environ.get(name)
    if raw is None:
        return defaults
    return tuple(group.strip() for group in raw.split(",") if group.strip())


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
    role_it_groups: tuple[str, ...] = DEFAULT_ROLE_IT_GROUPS
    role_central_groups: tuple[str, ...] = DEFAULT_ROLE_CENTRAL_GROUPS
    role_el_groups: tuple[str, ...] = DEFAULT_ROLE_EL_GROUPS
    role_pdl_groups: tuple[str, ...] = DEFAULT_ROLE_PDL_GROUPS
    organization_group_prefix: str = "ORG_"
    organization_scope_prefix: str = "ORG_ML_H"
    central_organization_group: str = "ORG_ML_H001"
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
            role_it_groups=_group_names("ML_ENROLL_ROLE_IT_GROUPS", DEFAULT_ROLE_IT_GROUPS),
            role_central_groups=_group_names(
                "ML_ENROLL_ROLE_CENTRAL_GROUPS", DEFAULT_ROLE_CENTRAL_GROUPS
            ),
            role_el_groups=_group_names("ML_ENROLL_ROLE_EL_GROUPS", DEFAULT_ROLE_EL_GROUPS),
            role_pdl_groups=_group_names(
                "ML_ENROLL_ROLE_PDL_GROUPS", DEFAULT_ROLE_PDL_GROUPS
            ),
            organization_group_prefix=os.environ.get("ML_ENROLL_ORG_PREFIX", "ORG_").strip(),
            organization_scope_prefix=os.environ.get(
                "ML_ENROLL_ORG_SCOPE_PREFIX", "ORG_ML_H"
            ).strip(),
            central_organization_group=os.environ.get(
                "ML_ENROLL_CENTRAL_ORG_GROUP", "ORG_ML_H001"
            ).strip(),
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
        configured_role_groups = (
            self.role_it_groups,
            self.role_central_groups,
            self.role_el_groups,
            self.role_pdl_groups,
        )
        if any(not groups for groups in configured_role_groups):
            raise RuntimeError("Enrollment role group lists must not be empty")
        role_groups = [group for groups in configured_role_groups for group in groups]
        if any(not group.startswith("BR_") for group in role_groups):
            raise RuntimeError("Enrollment role groups must use the BR_ namespace")
        if len(role_groups) != len(set(role_groups)):
            raise RuntimeError("Enrollment role group names must be unique")
        if not self.organization_scope_prefix.startswith(self.organization_group_prefix):
            raise RuntimeError("The organization scope prefix must be inside the ORG namespace")
        if not self.central_organization_group.startswith(self.organization_scope_prefix):
            raise RuntimeError("The central organization must be inside the managed ORG scope")
