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
class RegisteredDevice:
    device_uuid: str
    name: str
    last_seen: datetime | None


@dataclass(frozen=True)
class IssuedEnrollment:
    token_uuid: str
    token: str
    mode: str
    target_label: str
    expires: datetime
    replaced_devices: tuple[RegisteredDevice, ...] = ()

    def deep_link(self) -> str:
        return "de.missionleben.portal://enroll?" + urlencode(
            {
                "token": self.token,
                "token_id": self.token_uuid,
                "mode": self.mode,
            }
        )

    def install_link(self, public_origin: str) -> str:
        # URL fragments are not sent to the web server, reverse proxy, or
        # Referer targets. The short-lived enrollment credential stays local
        # to the phone until it is handed to the Android app.
        return public_origin.rstrip("/") + "/install#" + urlencode(
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

    async def device_status(self, agent_token: str) -> dict[str, Any]:
        device_uuid = await self.authentik.agent_device_id(agent_token)
        device = await self.authentik.device(device_uuid)
        if self._is_inactive(device):
            raise AuthentikError(403, "Das Gerät ist deaktiviert oder abgelaufen.")
        access_group_uuid = str(device.get("access_group") or "")
        if not access_group_uuid:
            raise AuthentikError(403, "Das Gerät besitzt keine gültige Zugriffsgruppe.")
        access_group = await self.authentik.access_group(access_group_uuid)
        attributes = access_group.get("attributes") or {}
        if attributes.get("mission-leben.de/purpose") != "android-portal":
            raise AuthentikError(403, "Das Gerät gehört nicht zum mobilen Portal.")
        mode = attributes.get("mission-leben.de/mode")
        if mode == "personal":
            await self._assert_active_personal_binding(access_group_uuid)
        elif mode != "shared":
            raise AuthentikError(403, "Die Gerätegruppe besitzt keinen gültigen Gerätetyp.")
        return {"device_id": device_uuid, "trusted": True}

    async def _assert_active_personal_binding(self, access_group_uuid: str) -> None:
        bindings = [
            binding
            for binding in await self.authentik.bindings(access_group_uuid)
            if binding.get("enabled")
            and not binding.get("negate")
            and binding.get("policy") is None
        ]
        direct_users = [
            binding
            for binding in bindings
            if binding.get("user") is not None and binding.get("group") is None
        ]
        if len(bindings) != 1 or len(direct_users) != 1:
            raise AuthentikError(
                403,
                "Die persönliche Gerätebindung ist nicht eindeutig.",
            )
        user = await self.authentik.user_record(int(direct_users[0]["user"]))
        if not user.get("is_active") or user.get("type") == "service_account":
            raise AuthentikError(403, "Der zugeordnete Mitarbeiter ist deaktiviert.")

    async def organizations_for(self, actor: Actor) -> list[dict[str, Any]]:
        groups = await self.authentik.organization_groups(
            None if actor.has_global_scope else actor.organization_names
        )
        return sorted(groups, key=lambda group: group["name"].casefold())

    async def employees_for(self, actor: Actor, search: str) -> list[dict[str, Any]]:
        allowed = None if actor.has_global_scope else actor.organization_names
        employees = await self.authentik.employees(search, allowed)
        if not employees:
            return []
        devices_by_user = await self._active_personal_devices_by_user()
        return [
            {
                **employee,
                "personal_devices": devices_by_user.get(str(employee["uuid"]), ()),
            }
            for employee in employees
        ]

    async def personal_devices_for_username(self, username: str) -> tuple[RegisteredDevice, ...]:
        user = await self.authentik.employee_by_username(username)
        return (await self._active_personal_devices_by_user()).get(str(user["uuid"]), ())

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
        replaced_devices = await self._active_devices(access_group["pbm_uuid"])
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
            replaced_devices=replaced_devices,
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
        replaced_devices: tuple[RegisteredDevice, ...] = (),
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
        return IssuedEnrollment(
            token_uuid,
            token,
            mode,
            target_label,
            expires,
            replaced_devices,
        )

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

            access_group_uuid = str(
                access_group.get("pbm_uuid") or token_record.get("device_group") or ""
            )
            try:
                UUID(access_group_uuid)
            except ValueError as error:
                raise AuthentikError(
                    502, "Authentik hat keine gültige Gerätegruppe geliefert."
                ) from error
            previous_devices = (
                await self._active_devices(access_group_uuid) if mode == "personal" else ()
            )

            response = await self.authentik.enroll_agent(
                presented_token,
                {"device_serial": device_serial, "device_name": clean_name},
            )
            try:
                await self.authentik.delete_enrollment_token(token_uuid)
            except Exception:
                LOGGER.exception("token deletion failed; forcing expiry for %s", token_uuid)
                await self.authentik.expire_enrollment_token(token_record)

            replaced_devices: list[RegisteredDevice] = []
            if mode == "personal":
                new_device_uuid = await self._replace_personal_devices(
                    response,
                    access_group_uuid,
                    previous_devices,
                )
                replaced_devices = [
                    device
                    for device in previous_devices
                    if device.device_uuid != new_device_uuid
                ]

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
                        "replaced_device_uuids": [
                            device.device_uuid for device in replaced_devices
                        ],
                    },
                )
            except Exception:
                LOGGER.exception("failed to audit redeemed enrollment token %s", token_uuid)
            return response

    async def _replace_personal_devices(
        self,
        enrollment_response: dict[str, Any],
        access_group_uuid: str,
        previous_devices: tuple[RegisteredDevice, ...],
    ) -> str:
        agent_token = str(enrollment_response.get("token", ""))
        if not agent_token:
            raise AuthentikError(502, "Authentik hat keinen Geräteschlüssel geliefert.")
        new_device_uuid = await self.authentik.agent_device_id(agent_token)
        new_device = await self.authentik.device(new_device_uuid)
        if str(new_device.get("access_group") or "") != access_group_uuid:
            await self._disable_new_device_after_failed_replacement(new_device_uuid)
            raise AuthentikError(
                502,
                "Das neue Gerät wurde nicht der erwarteten Gerätegruppe zugeordnet.",
            )

        to_disable = [
            device for device in previous_devices if device.device_uuid != new_device_uuid
        ]
        if not to_disable:
            return new_device_uuid

        disabled_at = datetime.now(UTC)
        try:
            for device in to_disable:
                await self.authentik.disable_device(
                    device.device_uuid, disabled_at, "replaced"
                )
        except Exception as error:
            await self._disable_new_device_after_failed_replacement(new_device_uuid)
            raise AuthentikError(
                502,
                "Der sichere Geräteaustausch konnte nicht abgeschlossen werden. "
                "Das neue Gerät wurde vorsorglich gesperrt; bitte erzeugen Sie einen neuen QR-Code.",
            ) from error
        return new_device_uuid

    async def _disable_new_device_after_failed_replacement(self, device_uuid: str) -> None:
        try:
            await self.authentik.disable_device(
                device_uuid, datetime.now(UTC), "replacement-failed"
            )
        except Exception:
            LOGGER.critical(
                "failed to disable newly enrolled device %s after replacement failure",
                device_uuid,
                exc_info=True,
            )

    async def _active_personal_devices_by_user(
        self,
    ) -> dict[str, tuple[RegisteredDevice, ...]]:
        devices_by_user: dict[str, list[RegisteredDevice]] = {}
        for device in await self.authentik.devices():
            if self._is_inactive(device):
                continue
            attributes = (device.get("access_group_obj") or {}).get("attributes", {})
            if attributes.get("mission-leben.de/mode") != "personal":
                continue
            user_uuid = str(attributes.get("mission-leben.de/user-uuid", ""))
            if not user_uuid:
                continue
            devices_by_user.setdefault(user_uuid, []).append(self._registered_device(device))
        return {
            user_uuid: tuple(sorted(devices, key=lambda item: item.name.casefold()))
            for user_uuid, devices in devices_by_user.items()
        }

    async def _active_devices(
        self, access_group_uuid: str
    ) -> tuple[RegisteredDevice, ...]:
        return tuple(
            self._registered_device(device)
            for device in await self.authentik.devices(access_group_uuid)
            if not self._is_inactive(device)
        )

    @staticmethod
    def _registered_device(device: dict[str, Any]) -> RegisteredDevice:
        facts = device.get("facts") or {}
        last_seen_raw = facts.get("created")
        last_seen = None
        if last_seen_raw:
            try:
                last_seen = datetime.fromisoformat(str(last_seen_raw).replace("Z", "+00:00"))
            except ValueError:
                LOGGER.warning("invalid device fact timestamp for %s", device.get("device_uuid"))
        return RegisteredDevice(
            device_uuid=str(device["device_uuid"]),
            name=str(device.get("name") or "Unbenanntes Gerät"),
            last_seen=last_seen,
        )

    @staticmethod
    def _is_disabled(device: dict[str, Any]) -> bool:
        return (
            device.get("attributes") or {}
        ).get("mission-leben.de/status") == "disabled"

    @classmethod
    def _is_inactive(cls, device: dict[str, Any]) -> bool:
        return cls._is_disabled(device) or cls._is_expired(device)

    @staticmethod
    def _is_expired(device: dict[str, Any]) -> bool:
        if not device.get("expiring"):
            return False
        expires_raw = device.get("expires")
        if not expires_raw:
            return False
        try:
            expires = datetime.fromisoformat(str(expires_raw).replace("Z", "+00:00"))
        except ValueError:
            LOGGER.warning("invalid device expiry for %s", device.get("device_uuid"))
            return False
        return expires <= datetime.now(UTC)

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
            "roles": sorted(role.value for role in actor.roles),
            "organization_scopes": sorted(actor.organization_names),
        }
