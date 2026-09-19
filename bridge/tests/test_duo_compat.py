from __future__ import annotations

import base64
import hashlib
import hmac
import json
import unittest
from email.utils import formatdate

from mission_leben_bridge.duo_compat import DuoCompatApi, DuoCompatSettings


class FakeApprovalService:
    def __init__(self, approved: bool):
        self.approved = approved
        self.calls: list[dict[str, object]] = []

    def request_login_approval(self, **values: object) -> bool:
        self.calls.append(values)
        return self.approved


def signed_headers(
    *, integration_key: str, secret: bytes, hostname: str, body: bytes
) -> dict[str, str]:
    date = formatdate(usegmt=True)
    canonical = "\n".join(
        (
            date,
            "POST",
            hostname,
            "/auth/v2/auth",
            "",
            hashlib.sha512(body).hexdigest(),
            hashlib.sha512(b"").hexdigest(),
        )
    ).encode()
    signature = hmac.new(secret, canonical, hashlib.sha512).hexdigest()
    authorization = base64.b64encode(f"{integration_key}:{signature}".encode()).decode()
    return {"Host": hostname, "Date": date, "Authorization": f"Basic {authorization}"}


class DuoCompatTest(unittest.TestCase):
    def test_signed_auth_request_maps_approval_to_allow(self) -> None:
        integration_key = "mission-leben-authentik"
        secret = b"s" * 40
        hostname = "id.example.invalid"
        service = FakeApprovalService(True)
        api = DuoCompatApi(
            DuoCompatSettings(integration_key, secret, hostname, 60),
            service,  # type: ignore[arg-type]
        )
        body = json.dumps(
            {
                "factor": "auto",
                "async": "0",
                "user_id": "authentik-user-1",
                "display_username": "test.user",
                "ipaddr": "192.0.2.20",
                "pushinfo": "Domain=id.example.invalid&Application=Zimbra",
            },
            sort_keys=True,
            separators=(",", ":"),
        ).encode()

        status, response = api.handle(
            "POST",
            "/auth/v2/auth",
            "",
            body,
            signed_headers(
                integration_key=integration_key,
                secret=secret,
                hostname=hostname,
                body=body,
            ),
        )

        self.assertEqual(200, status)
        self.assertEqual("allow", response["response"]["result"])  # type: ignore[index]
        self.assertEqual("authentik-user-1", service.calls[0]["subject"])
        self.assertEqual("Zimbra", service.calls[0]["application"])

    def test_service_failure_is_always_explicit_deny(self) -> None:
        integration_key = "mission-leben-authentik"
        secret = b"s" * 40
        hostname = "id.example.invalid"

        class FailingService:
            def request_login_approval(self, **_: object) -> bool:
                raise RuntimeError("unavailable")

        api = DuoCompatApi(
            DuoCompatSettings(integration_key, secret, hostname, 60),
            FailingService(),  # type: ignore[arg-type]
        )
        body = b'{"async":"0","factor":"auto","user_id":"user-1"}'
        _, response = api.handle(
            "POST",
            "/auth/v2/auth",
            "",
            body,
            signed_headers(
                integration_key=integration_key,
                secret=secret,
                hostname=hostname,
                body=body,
            ),
        )
        self.assertEqual("deny", response["response"]["result"])  # type: ignore[index]


if __name__ == "__main__":
    unittest.main()
