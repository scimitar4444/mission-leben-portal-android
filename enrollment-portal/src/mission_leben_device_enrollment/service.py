from __future__ import annotations

import asyncio
import logging
import re
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta
from typing import Any
from urllib.parse import urlencode
from uuid import UUID

from .auth import Actor
from .authentik import AuthentikClient, AuthentikError
from .config import Settings


LOGGER = logging.getLogger("mission_leben_device_enrollment")
PERSONAL_PREFIX = "Mission Leben Android - Personal - "
SHARED_PREFIX = "Mission Leben Android - Shared - "
DEVICE_SERIAL = re.compile(r"ml-android-[0-9a-f]{16,128}", re.IGNORECASE)


@dataclass(frozen=True)
class IssuedEnrollment:
    token_uuid: str
    token: str
    mode: str
    target_label: str
    expires: datetime

    def qr_payload(self) -> str:
        return "de.missionleben.portal://enroll?" + urlencode(
            {
                "token": self.token,
                "token_id": self.token_uuid,
                "mode": self.mode,
            }
        )


class EnrollmentService:
    def __init__(self, settings: Settings, authentik: AuthentikClient):
        self.settings = settings
        self.authentik = authentik
        self._redemption_lock = asyncio.Lock()

    async def organizations_for(self, actor: Actor) -> list[dict[str, Any]]:
        groups = await self.authentik.organization_groups(
            None if actor.has_global_scope else actor.organization_names
        )
        return sorted(groups, key=lambda group: group["name"].casefold())

    async def employees_for(self, actor: Actor, search: str) -> list[dict[str, Any]]:
        allowed = None if actor.has_global_scope else actor.organization_names
        return await self.authentik.employees(search, allowed)

    async def issue_personal(self, actor: Actor, user_pk: int) -> IssuedEnrollment:
        user = await self.authentik.employee(user_pk)
        self._assert_employee_scope(actor, user)
        return await self._issue_personal_for_user(actor, user, self_service=False)

    async def issue_self_personal(self, actor: Actor) -> IssuedEnrollment:
        user = await self.authentik.employee_by_username(actor.username)
        return await self._issue_personal_for_user(actor, user, self_service=True)

    async def _issue_personal_for_user(
        self,
        actor: Actor,
        user: dict[str, Any],
        *,
        self_service: bool,
    ) -> IssuedEnrollment:
        user_uuid = str(user["uuid"])
        user_pk = int(user["pk"])
        group_name = PERSONAL_PREFIX + user_uuid
        attributes = {
            "mission-leben.de/purpose": "android-portal",
            "mission-leben.de/status": "active",
            "mission-leben.de/mode": "personal",
            "mission-leben.de/user-uuid": user_uuid,
            "mission-leben.de/username": user["username"],
        }
        access_group = await self._access_group(group_name, attributes)
        await self._ensure_binding(access_group["pbm_uuid"], user_pk=user_pk)
        subject = str(user.get("uid", "")).strip()
        if not subject:
            raise AuthentikError(502, "Authentik hat keine stabile Benutzerkennung geliefert.")
        await self.authentik.ensure_login_approval_device(user["username"], subject)
        return await self._issue(
            actor=actor,
            access_group=access_group,
            mode="personal",
            target_label=user.get("name") or user["username"],
            target={
                "user_pk": user_pk,
                "user_uuid": user_uuid,
                "username": user["username"],
                "self_service": self_service,
            },
        )

    async def issue_shared(
        self, actor: Actor, organization_uuid: str, device_label: str
    ) -> IssuedEnrollment:
        organization = await self.authentik.organization_group(organization_uuid)
        if not actor.has_global_scope and organization["name"] not in actor.organization_names:
            raise AuthentikError(403, "Diese Einrichtung ist Ihrem Konto nicht zugeordnet.")
        normalized_label = " ".join(device_label.split()).strip()
        if len(normalized_label) not in range(2, 81):
            raise AuthentikError(400, "Bitte geben Sie einen Gerätenamen mit 2 bis 80 Zeichen ein.")
        group_name = SHARED_PREFIX + organization["name"]
        attributes = {
            "mission-leben.de/purpose": "android-portal",
            "mission-leben.de/status": "active",
            "mission-leben.de/mode": "shared",
            "mission-leben.de/facility-group": organization["name"],
        }
        access_group = await self._access_group(group_name, attributes)
        await self._ensure_binding(access_group["pbm_uuid"], group_uuid=organization["pk"])
        return await self._issue(
            actor=actor,
            access_group=access_group,
            mode="shared",
            target_label=f"{organization['name']} – {normalized_label}",
            target={
                "organization_uuid": organization["pk"],
                "organization": organization["name"],
                "device_label": normalized_label,
            },
        )

    async def _access_group(self, name: str, attributes: dict[str, Any]) -> dict[str, Any]:
        group = await self.authentik.access_group_by_name(name)
        if group is None:
            return await self.authentik.create_access_group(name, attributes)
        existing = group.get("attributes", {})
        for key in ("mission-leben.de/purpose", "mission-leben.de/mode"):
            if existing.get(key) not in {None, attributes[key]}:
                raise AuthentikError(409, "Die vorhandene Gerätegruppe hat eine unerwartete Konfiguration.")
        return await self.authentik.update_access_group(
            group["pbm_uuid"], name, {**existing, **attributes}
        )

    async def _ensure_binding(
        self,
        target_uuid: str,
        *,
        user_pk: int | None = None,
        group_uuid: str | None = None,
    ) -> None:
        bindings = [binding for binding in await self.authentik.bindings(target_uuid) if binding.get("enabled")]
        expected = [
            binding
            for binding in bindings
            if binding.get("user") == user_pk
            and binding.get("group") == group_uuid
            and binding.get("policy") is None
            and not binding.get("negate")
        ]
        if len(bindings) == 1 and len(expected) == 1:
            return
        if bindings:
            raise AuthentikError(
                409,
                "Die Gerätegruppe besitzt eine unerwartete Bindung. Bitte wenden Sie sich an die IT.",
            )
        if user_pk is not None:
            await self.authentik.create_user_binding(target_uuid, user_pk)
        elif group_uuid is not None:
            await self.authentik.create_group_binding(target_uuid, group_uuid)
        else:
            raise ValueError("A user or group binding is required")

    async def _issue(
        self,
        *,
        actor: Actor,
        access_group: dict[str, Any],
        mode: str,
        target_label: str,
        target: dict[str, Any],
    ) -> IssuedEnrollment:
        expires = datetime.now(UTC) + timedelta(seconds=self.settings.token_ttl_seconds)
        token_record = await self.authentik.create_enrollment_token(
            name=f"Mission Leben {mode} durch {actor.username} bis {expires:%Y-%m-%d %H:%MZ}",
            access_group_uuid=access_group["pbm_uuid"],
            expires=expires,
        )
        token_uuid = token_record["token_uuid"]
        try:
            token = await self.authentik.enrollment_token_key(token_uuid)
            await self.authentik.audit(
                "model_created",
                self._actor_audit(actor),
                {
                    "message": "Mission Leben Geräteinitialisierung erstellt",
                    "token_uuid": token_uuid,
                    "mode": mode,
                    "access_group": access_group["name"],
                    "expires": expires.isoformat(),
                    "target": target,
                },
            )
        except Exception:
            try:
                await self.authentik.delete_enrollment_token(token_uuid)
            except Exception:
                LOGGER.exception("failed to clean up enrollment token %s", token_uuid)
            raise
        return IssuedEnrollment(token_uuid, token, mode, target_label, expires)

    async def redeem(
        self,
        token_uuid: str,
        presented_token: str,
        mode: str,
        device_serial: str,
        device_name: str,
    ) -> dict[str, Any]:
        UUID(token_uuid)
        if mode not in {"personal", "shared"}:
            raise AuthentikError(400, "Ungültiger Gerätemodus.")
        if not DEVICE_SERIAL.fullmatch(device_serial):
            raise AuthentikError(400, "Ungültige Gerätekennung.")
        clean_name = " ".join(device_name.split()).strip()
        if len(clean_name) not in range(2, 121):
            raise AuthentikError(400, "Ungültiger Gerätename.")

        # The deployment deliberately runs a single worker. This lock makes the
        # read/enroll/delete sequence one-use within the stateless container.
        async with self._redemption_lock:
            token_record = await self.authentik.enrollment_token(token_uuid)
            if token_record.get("connector") != self.settings.agent_connector_uuid:
                raise AuthentikError(403, "Der Registrierungscode gehört zu einem anderen Connector.")
            expires = datetime.fromisoformat(str(token_record["expires"]).replace("Z", "+00:00"))
            if not token_record.get("expiring") or expires <= datetime.now(UTC):
                raise AuthentikError(410, "Der Registrierungscode ist abgelaufen.")
            if not await self.authentik.token_matches(token_uuid, presented_token):
                raise AuthentikError(401, "Der Registrierungscode ist ungültig.")
            access_group = token_record.get("device_group_obj") or {}
            if access_group.get("attributes", {}).get("mission-leben.de/mode") != mode:
                raise AuthentikError(403, "Der Registrierungscode passt nicht zum Gerätemodus.")

            response = await self.authentik.enroll_agent(
                presented_token,
                {"device_serial": device_serial, "device_name": clean_name},
            )
            try:
                await self.authentik.delete_enrollment_token(token_uuid)
            except Exception:
                LOGGER.exception("token deletion failed; forcing expiry for %s", token_uuid)
                await self.authentik.expire_enrollment_token(token_record)

            try:
                await self.authentik.audit(
                    "model_updated",
                    {"username": "device-enrollment", "uid": device_serial},
                    {
                        "message": "Mission Leben Geräteinitialisierung eingelöst",
                        "token_uuid": token_uuid,
                        "mode": mode,
                        "device_serial": device_serial,
                        "device_name": clean_name,
                        "access_group": access_group.get("name"),
                    },
                )
            except Exception:
                LOGGER.exception("failed to audit redeemed enrollment token %s", token_uuid)
            return response

    def _assert_employee_scope(self, actor: Actor, user: dict[str, Any]) -> None:
        if actor.has_global_scope:
            return
        employee_organizations = {
            group.get("name")
            for group in user.get("groups_obj") or []
            if self.authentik._is_organization(group)
        }
        if not employee_organizations & actor.organization_names:
            raise AuthentikError(403, "Dieser Mitarbeiter gehört nicht zu Ihrem Bereich.")

    @staticmethod
    def _actor_audit(actor: Actor) -> dict[str, Any]:
        return {
            "username": actor.username,
            "name": actor.display_name,
            "uid": actor.uid,
            "role": actor.role.value if actor.role is not None else "self_totp",
        }
