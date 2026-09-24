from __future__ import annotations

import hmac
import re
from datetime import UTC, datetime
from typing import Any
from uuid import UUID

import httpx

from .config import Settings


class AuthentikError(RuntimeError):
    def __init__(self, status: int, message: str):
        self.status = status
        super().__init__(message)


def is_personal_employee(user: dict[str, Any]) -> bool:
    attributes = user.get("attributes") or {}
    return bool(
        user.get("is_active")
        and user.get("type") != "service_account"
        and attributes.get("iam_account_kind") == "person"
        and attributes.get("iam_directory_class") == "person"
    )


def is_shared_handset_account(user: dict[str, Any]) -> bool:
    """Return only interactive shared mailboxes eligible for an IT-owned handset.

    This is an enrollment preflight, not an authorization decision. Device
    ownership, direct binding and endpoint trust are checked separately.
    """
    attributes = user.get("attributes") or {}
    return bool(
        user.get("is_active")
        and user.get("type") != "service_account"
        and attributes.get("iam_account_kind") == "shared"
        and attributes.get("iam_directory_class") == "mailbox"
        and attributes.get("iam_interactive_login_allowed") is True
        and attributes.get("iam_noninteractive_account") is False
    )


def is_real_facility(group: dict[str, Any], organization_scope_prefix: str) -> bool:
    name = str(group.get("name", ""))
    attributes = group.get("attributes") or {}
    if not re.fullmatch(
        rf"{re.escape(organization_scope_prefix)}[0-9]{{3}}(?:_[0-9]{{2}})?",
        name,
    ):
        return False
    if (
        attributes.get("iam_group_type") != "organization_unit"
        or attributes.get("iam_managed") is not True
        or attributes.get("iam_plan_status") not in {"UMGESETZT_UEBERGANG", "AKTIV"}
    ):
        return False
    organization_level = attributes.get("iam_org_level")
    return organization_level in {"Einrichtung", "Einrichtung/Verbund"} or (
        name == "ORG_ML_H001"
        and organization_level == "Geschäftseinheit/Standort"
    )


class AuthentikClient:
    def __init__(self, settings: Settings, transport: httpx.AsyncBaseTransport | None = None):
        self.settings = settings
        self._client = httpx.AsyncClient(
            base_url=settings.authentik_base_url + "/api/v3",
            headers={
                "Accept": "application/json",
                "Authorization": f"Bearer {settings.authentik_api_token}",
                "User-Agent": "MissionLebenDeviceEnrollment/0.1.0",
            },
            timeout=httpx.Timeout(15.0, connect=5.0),
            follow_redirects=False,
            transport=transport,
        )

    async def close(self) -> None:
        await self._client.aclose()

    async def _all_results(self, path: str, params: dict[str, Any]) -> list[dict[str, Any]]:
        results: list[dict[str, Any]] = []
        page = 1
        while True:
            payload = await self._request("GET", path, params={**params, "page": page})
            results.extend(payload["results"])
            total_pages = int(payload.get("pagination", {}).get("total_pages", 1))
            if page >= total_pages:
                return results
            page += 1

    async def _request(
        self,
        method: str,
        path: str,
        *,
        expected: set[int] = {200},
        params: Any = None,
        json: dict[str, Any] | None = None,
        authorization: str | None = None,
    ) -> Any:
        headers = {"Authorization": authorization} if authorization else None
        response = await self._client.request(method, path, params=params, json=json, headers=headers)
        if response.status_code not in expected:
            detail = ""
            try:
                payload = response.json()
                detail = str(payload.get("detail") or payload.get("error") or payload)
            except Exception:
                detail = response.text[:300]
            raise AuthentikError(response.status_code, detail or "Authentik request failed")
        if response.status_code == 204 or not response.content:
            return None
        return response.json()

    async def organization_groups(self, names: set[str] | frozenset[str] | None = None) -> list[dict[str, Any]]:
        if names is None:
            candidates = await self._all_results(
                "/core/groups/",
                {"search": self.settings.organization_group_prefix, "page_size": 100},
            )
        else:
            candidates = []
            for name in sorted(names):
                payload = await self._request(
                    "GET", "/core/groups/", params={"name": name, "include_users": "false", "page_size": 2}
                )
                candidates.extend(payload["results"])
        return [group for group in candidates if self._is_organization(group)]

    def _is_organization(self, group: dict[str, Any]) -> bool:
        return is_real_facility(group, self.settings.organization_scope_prefix)

    async def organization_group(self, group_uuid: str) -> dict[str, Any]:
        UUID(group_uuid)
        group = await self._request("GET", f"/core/groups/{group_uuid}/")
        if not self._is_organization(group):
            raise AuthentikError(400, "Die gewählte Gruppe ist keine freigegebene Einrichtung.")
        return group

    async def employees(self, search: str, allowed_groups: frozenset[str] | None) -> list[dict[str, Any]]:
        search = search.strip()
        if len(search) < 2:
            return []
        results: dict[int, dict[str, Any]] = {}
        group_filters: list[str | None] = [None] if allowed_groups is None else sorted(allowed_groups)
        for group_name in group_filters:
            params: list[tuple[str, str | int]] = [
                ("search", search),
                ("is_active", "true"),
                ("include_groups", "true"),
                ("include_roles", "false"),
                ("page_size", 50),
            ]
            if group_name:
                params.append(("groups_by_name", group_name))
            payload = await self._request("GET", "/core/users/", params=params)
            for user in payload["results"]:
                if is_personal_employee(user):
                    results[int(user["pk"])] = user
        if allowed_groups is not None:
            results = {
                pk: user
                for pk, user in results.items()
                if {
                    group.get("name")
                    for group in user.get("groups_obj") or []
                    if self._is_organization(group)
                }
                & allowed_groups
            }
        return sorted(results.values(), key=lambda user: (user.get("name") or user["username"]).casefold())[:50]

    async def employee(self, user_pk: int) -> dict[str, Any]:
        user = await self.user_record(user_pk)
        if not is_personal_employee(user):
            raise AuthentikError(400, "Dieser Mitarbeiter kann nicht ausgewählt werden.")
        return user

    async def shared_handset_accounts(self, search: str) -> list[dict[str, Any]]:
        search = search.strip()
        if len(search) < 2:
            return []
        payload = await self._request(
            "GET",
            "/core/users/",
            params={
                "search": search,
                "is_active": "true",
                "include_groups": "false",
                "include_roles": "false",
                "page_size": 50,
            },
        )
        return sorted(
            (user for user in payload["results"] if is_shared_handset_account(user)),
            key=lambda user: (user.get("name") or user["username"]).casefold(),
        )[:50]

    async def shared_handset_account(self, user_pk: int) -> dict[str, Any]:
        user = await self.user_record(user_pk)
        if not is_shared_handset_account(user):
            raise AuthentikError(400, "Dieses Gruppenkonto ist nicht für ein Diensthandy freigegeben.")
        return user

    async def user_record(self, user_pk: int) -> dict[str, Any]:
        return await self._request("GET", f"/core/users/{user_pk}/")

    async def employee_by_username(self, username: str) -> dict[str, Any]:
        payload = await self._request(
            "GET",
            "/core/users/",
            params={"username": username, "include_groups": "true", "page_size": 2},
        )
        matches = [
            user
            for user in payload["results"]
            if str(user.get("username", "")).casefold() == username.casefold()
        ]
        if len(matches) != 1:
            raise AuthentikError(403, "Das angemeldete Mitarbeiterkonto ist nicht eindeutig.")
        user = matches[0]
        if not is_personal_employee(user):
            raise AuthentikError(403, "Dieses Mitarbeiterkonto darf kein Gerät registrieren.")
        return user

    async def ensure_login_approval_device(self, username: str, subject: str) -> None:
        stage_uuid = self.settings.app_approval_stage_uuid
        if not stage_uuid:
            return
        payload = await self._request(
            "POST",
            f"/stages/authenticator/duo/{stage_uuid}/import_device_manual/",
            expected={204, 400},
            json={"username": username, "duo_user_id": subject},
        )
        if payload is None:
            return
        errors = payload.get("non_field_errors", []) if isinstance(payload, dict) else []
        if any("exists already" in str(error).lower() for error in errors):
            return
        raise AuthentikError(409, "Die App-Bestätigung konnte nicht zugeordnet werden.")

    async def access_group_by_name(self, name: str) -> dict[str, Any] | None:
        results = await self._all_results(
            "/endpoints/device_access_groups/", {"search": name, "page_size": 100}
        )
        return next((item for item in results if item["name"] == name), None)

    async def access_groups(self) -> list[dict[str, Any]]:
        return await self._all_results(
            "/endpoints/device_access_groups/", {"page_size": 100}
        )

    async def access_group(self, group_uuid: str) -> dict[str, Any]:
        UUID(group_uuid)
        return await self._request("GET", f"/endpoints/device_access_groups/{group_uuid}/")

    async def create_access_group(self, name: str, attributes: dict[str, Any]) -> dict[str, Any]:
        return await self._request(
            "POST",
            "/endpoints/device_access_groups/",
            expected={201},
            json={"name": name, "attributes": attributes},
        )

    async def update_access_group(self, group_uuid: str, name: str, attributes: dict[str, Any]) -> dict[str, Any]:
        return await self._request(
            "PATCH",
            f"/endpoints/device_access_groups/{group_uuid}/",
            json={"name": name, "attributes": attributes},
        )

    async def bindings(self, target_uuid: str) -> list[dict[str, Any]]:
        return await self._all_results(
            "/endpoints/device_bindings/", {"target": target_uuid, "page_size": 100}
        )

    async def create_user_binding(self, target_uuid: str, user_pk: int) -> dict[str, Any]:
        return await self._request(
            "POST",
            "/endpoints/device_bindings/",
            expected={201},
            json={
                "target": target_uuid,
                "user": user_pk,
                "group": None,
                "policy": None,
                "order": 0,
                "enabled": True,
                "negate": False,
                "is_primary": True,
            },
        )

    async def create_group_binding(self, target_uuid: str, group_uuid: str) -> dict[str, Any]:
        return await self._request(
            "POST",
            "/endpoints/device_bindings/",
            expected={201},
            json={
                "target": target_uuid,
                "user": None,
                "group": group_uuid,
                "policy": None,
                "order": 0,
                "enabled": True,
                "negate": False,
                "is_primary": True,
            },
        )

    async def devices(self, access_group_uuid: str | None = None) -> list[dict[str, Any]]:
        devices = await self._all_results(
            "/endpoints/devices/", {"page_size": 100}
        )
        if access_group_uuid is None:
            return devices
        return [
            device
            for device in devices
            if str(device.get("access_group") or "") == access_group_uuid
        ]

    async def device(self, device_uuid: str) -> dict[str, Any]:
        UUID(device_uuid)
        return await self._request("GET", f"/endpoints/devices/{device_uuid}/")

    async def update_device_assignment(
        self,
        device_uuid: str,
        display_name: str,
        mode: str,
        assigned_to: str,
        *,
        handset_profile: str | None = None,
    ) -> dict[str, Any]:
        UUID(device_uuid)
        if mode not in {"personal", "shared"}:
            raise ValueError("invalid device mode")
        device = await self.device(device_uuid)
        attributes = {
            **(device.get("attributes") or {}),
            "mission-leben.de/purpose": "android-portal",
            "mission-leben.de/mode": mode,
            "mission-leben.de/assigned-kind": (
                "user" if mode == "personal" else "organization"
            ),
            "mission-leben.de/assigned-to": assigned_to,
        }
        if handset_profile is not None:
            if mode != "personal" or handset_profile != "shared-account":
                raise ValueError("invalid handset profile")
            attributes["mission-leben.de/handset-profile"] = handset_profile
            attributes["mission-leben.de/device-ownership"] = "company"
        return await self._request(
            "PATCH",
            f"/endpoints/devices/{device_uuid}/",
            json={"name": display_name, "attributes": attributes},
        )

    async def disable_device(
        self, device_uuid: str, disabled_at: datetime, reason: str
    ) -> dict[str, Any]:
        UUID(device_uuid)
        device = await self.device(device_uuid)
        attributes = {
            **(device.get("attributes") or {}),
            "mission-leben.de/status": "disabled",
            "mission-leben.de/disabled-at": disabled_at.astimezone(UTC)
            .isoformat()
            .replace("+00:00", "Z"),
            "mission-leben.de/disabled-reason": reason,
        }
        return await self._request(
            "PATCH",
            f"/endpoints/devices/{device_uuid}/",
            json={
                "attributes": attributes,
                "expiring": False,
                "expires": None,
            },
        )

    async def create_enrollment_token(
        self, name: str, access_group_uuid: str, expires: datetime
    ) -> dict[str, Any]:
        return await self._request(
            "POST",
            "/endpoints/agents/enrollment_tokens/",
            expected={201},
            json={
                "name": name,
                "connector": self.settings.agent_connector_uuid,
                "device_group": access_group_uuid,
                "expiring": True,
                "expires": expires.astimezone(UTC).isoformat().replace("+00:00", "Z"),
            },
        )

    async def enrollment_token(self, token_uuid: str) -> dict[str, Any]:
        UUID(token_uuid)
        return await self._request("GET", f"/endpoints/agents/enrollment_tokens/{token_uuid}/")

    async def enrollment_token_key(self, token_uuid: str) -> str:
        payload = await self._request(
            "GET", f"/endpoints/agents/enrollment_tokens/{token_uuid}/view_key/"
        )
        return str(payload["key"])

    async def delete_enrollment_token(self, token_uuid: str) -> None:
        await self._request(
            "DELETE", f"/endpoints/agents/enrollment_tokens/{token_uuid}/", expected={204}
        )

    async def expire_enrollment_token(self, token: dict[str, Any]) -> None:
        await self._request(
            "PATCH",
            f"/endpoints/agents/enrollment_tokens/{token['token_uuid']}/",
            json={
                "connector": token["connector"],
                "device_group": token.get("device_group"),
                "name": token["name"],
                "expiring": True,
                "expires": datetime.now(UTC).isoformat().replace("+00:00", "Z"),
            },
        )

    async def enroll_agent(self, enrollment_token: str, payload: dict[str, Any]) -> dict[str, Any]:
        return await self._request(
            "POST",
            "/endpoints/agents/connectors/enroll/",
            expected={200, 201},
            json=payload,
            authorization=f"Bearer {enrollment_token}",
        )

    async def agent_device_id(self, agent_token: str) -> str:
        payload = await self._request(
            "GET",
            "/endpoints/agents/connectors/agent_config/",
            authorization=f"Bearer+Agent {agent_token}",
        )
        device_uuid = str(payload.get("device_id", ""))
        UUID(device_uuid)
        return device_uuid

    async def audit(self, action: str, actor: dict[str, Any], context: dict[str, Any]) -> None:
        await self._request(
            "POST",
            "/events/events/",
            expected={201},
            json={
                "action": action,
                "app": "mission-leben-device-enrollment",
                "user": actor,
                "context": context,
            },
        )

    async def token_matches(self, token_uuid: str, presented: str) -> bool:
        expected = await self.enrollment_token_key(token_uuid)
        return hmac.compare_digest(expected, presented)
