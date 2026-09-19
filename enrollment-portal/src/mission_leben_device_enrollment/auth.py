from __future__ import annotations

import base64
import hashlib
import hmac
import time
from dataclasses import dataclass
from enum import Enum

from fastapi import HTTPException, Request

from .config import Settings


class Role(str, Enum):
    IT = "it"
    CENTRAL = "central"
    EL = "el"
    PDL = "pdl"


@dataclass(frozen=True)
class Actor:
    uid: str
    username: str
    display_name: str
    role: Role
    organization_names: frozenset[str]

    @property
    def has_global_scope(self) -> bool:
        return self.role is Role.IT

    @property
    def role_label(self) -> str:
        return {
            Role.IT: "IT",
            Role.CENTRAL: "Leitung Zentrale",
            Role.EL: "EL",
            Role.PDL: "PDL",
        }[self.role]


def actor_from_request(request: Request, settings: Settings) -> Actor:
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
    role = next(
        (
            candidate
            for group_name, candidate in (
                (settings.role_it_group, Role.IT),
                (settings.role_central_group, Role.CENTRAL),
                (settings.role_el_group, Role.EL),
                (settings.role_pdl_group, Role.PDL),
            )
            if group_name in groups
        ),
        None,
    )
    if role is None:
        raise HTTPException(403, "Sie dürfen keine Geräte initialisieren.")

    organizations = frozenset(
        name for name in groups if name.startswith(settings.organization_group_prefix)
    )
    if role is not Role.IT and not organizations:
        raise HTTPException(403, "Ihrem Konto ist keine Einrichtung zugeordnet.")
    return Actor(
        uid=uid,
        username=username,
        display_name=request.headers.get("x-authentik-name", "").strip() or username,
        role=role,
        organization_names=organizations,
    )


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
