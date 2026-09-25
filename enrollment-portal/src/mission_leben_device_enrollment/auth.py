from __future__ import annotations

import base64
import hashlib
import hmac
import re
import time
from dataclasses import dataclass
from enum import Enum

from fastapi import HTTPException, Request

from .config import DEPUTY_EL_GROUP, Settings


class Role(str, Enum):
    IT = "it"
    EL = "el"
    DEPUTY_EL = "deputy_el"
    PDL = "pdl"


@dataclass(frozen=True)
class Actor:
    uid: str
    username: str
    display_name: str
    roles: frozenset[Role]
    organization_names: frozenset[str]
    effective_group_names: frozenset[str] = frozenset()

    @property
    def has_global_scope(self) -> bool:
        return Role.IT in self.roles

    @property
    def can_initialize_shared_handset(self) -> bool:
        # This new privilege is intentionally narrower than configurable IT
        # scope used by the existing personal/tablet initialization paths.
        return Role.IT in self.roles and "BR_IT_MANAGEMENT" in self.effective_group_names

    @property
    def can_manage_devices(self) -> bool:
        return self.has_global_scope or bool(self.roles and self.organization_names)

    @property
    def role(self) -> Role | None:
        for candidate in (Role.IT, Role.EL, Role.DEPUTY_EL, Role.PDL):
            if candidate in self.roles:
                return candidate
        return None

    @property
    def role_label(self) -> str:
        labels = {
            Role.IT: "IT",
            Role.EL: "EL",
            Role.DEPUTY_EL: "Stellvertretende EL",
            Role.PDL: "PDL",
        }
        ordered = [labels[role] for role in Role if role in self.roles]
        return " / ".join(ordered) if ordered else "Mitarbeiter"


def _decode_utf8_proxy_header(value: str) -> str:
    """Restore UTF-8 text exposed through ASGI's Latin-1 header mapping."""
    try:
        return value.encode("latin-1").decode("utf-8")
    except (UnicodeEncodeError, UnicodeDecodeError):
        return value


def authenticated_actor_from_request(request: Request, settings: Settings) -> Actor:
    if request.headers.get("x-authentik-meta-app", "") != settings.proxy_app_slug:
        raise HTTPException(401, "Authentik-Schutz fehlt oder ist falsch konfiguriert.")
    uid = request.headers.get("x-authentik-uid", "").strip()
    username = request.headers.get("x-authentik-username", "").strip()
    if not uid or not username:
        raise HTTPException(401, "Authentik-Anmeldung fehlt.")

    groups = frozenset(
        value.strip()
        for value in request.headers.get("x-authentik-groups", "").split("|")
        if value.strip()
    )
    role_groups = (
        (settings.role_it_groups, Role.IT),
        (settings.role_el_groups, Role.EL),
        ((DEPUTY_EL_GROUP,), Role.DEPUTY_EL),
        (settings.role_pdl_groups, Role.PDL),
    )
    roles = frozenset(
        role for configured_groups, role in role_groups if groups.intersection(configured_groups)
    )
    scope_pattern = re.compile(
        rf"^{re.escape(settings.organization_scope_prefix)}[0-9]{{3}}(?:_[0-9]{{2}})?$"
    )
    scoped_organizations = {name for name in groups if scope_pattern.fullmatch(name)}
    organizations: set[str] = set()
    if roles.intersection({Role.EL, Role.DEPUTY_EL, Role.PDL}):
        organizations.update(scoped_organizations)
    return Actor(
        uid=uid,
        username=username,
        display_name=_decode_utf8_proxy_header(
            request.headers.get("x-authentik-name", "").strip()
        )
        or username,
        roles=roles,
        organization_names=frozenset(organizations),
        effective_group_names=groups,
    )


def actor_from_request(request: Request, settings: Settings) -> Actor:
    actor = authenticated_actor_from_request(request, settings)
    if not actor.roles:
        raise HTTPException(403, "Sie dürfen keine Geräte für andere Personen initialisieren.")
    if not actor.can_manage_devices:
        raise HTTPException(403, "Ihrem Konto ist keine Einrichtung zugeordnet.")
    return actor


class CsrfProtector:
    _WINDOW_SECONDS = 600

    def __init__(self, secret: str, public_origin: str):
        self._secret = secret.encode()
        self._public_origin = public_origin

    def issue(self, actor: Actor, action: str, now: int | None = None) -> str:
        bucket = (now if now is not None else int(time.time())) // self._WINDOW_SECONDS
        message = f"{actor.uid}\n{action}\n{bucket}".encode()
        digest = hmac.new(self._secret, message, hashlib.sha256).digest()
        return base64.urlsafe_b64encode(digest).decode().rstrip("=")

    def verify_request(self, request: Request, actor: Actor, action: str, token: str) -> None:
        origin = request.headers.get("origin", "").rstrip("/")
        referer = request.headers.get("referer", "")
        if origin != self._public_origin and not referer.startswith(self._public_origin + "/"):
            raise HTTPException(403, "Ungültige Anfragequelle.")
        now = int(time.time())
        if not any(
            hmac.compare_digest(token, self.issue(actor, action, now - offset))
            for offset in (0, self._WINDOW_SECONDS)
        ):
            raise HTTPException(403, "Die Seite ist abgelaufen. Bitte beginnen Sie erneut.")
