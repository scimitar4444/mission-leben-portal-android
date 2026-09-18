from __future__ import annotations

import html
import time
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from zoneinfo import ZoneInfo


SOAP = "http://www.w3.org/2003/05/soap-envelope"
ZIMBRA = "urn:zimbra"
ADMIN = "urn:zimbraAdmin"
MAIL = "urn:zimbraMail"


class ZimbraSoapError(Exception):
    pass


def _local_name(tag: str) -> str:
    return tag.rsplit("}", 1)[-1]


def _child_text(element: ET.Element, name: str) -> str:
    child = next((item for item in element if _local_name(item.tag) == name), None)
    return (child.text or "").strip() if child is not None else ""


@dataclass(frozen=True)
class ZimbraMessage:
    message_id: str
    received_millis: int
    sender: str
    subject: str
    fragment: str


@dataclass(frozen=True)
class ZimbraAppointment:
    appointment_id: str
    start_millis: int
    duration_millis: int
    subject: str
    location: str


class ZimbraSoapClient:
    def __init__(self, admin_soap_url: str, mail_soap_url: str, admin_user: str, admin_password: str):
        self.admin_soap_url = admin_soap_url
        self.mail_soap_url = mail_soap_url
        self.admin_user = admin_user
        self.admin_password = admin_password
        self.auth_token = ""

    def authenticate(self) -> None:
        request = ET.Element(f"{{{ADMIN}}}AuthRequest")
        ET.SubElement(request, f"{{{ADMIN}}}name").text = self.admin_user
        ET.SubElement(request, f"{{{ADMIN}}}password").text = self.admin_password
        response = self._post(self.admin_soap_url, request, include_context=False)
        token = next((node.text for node in response.iter() if _local_name(node.tag) == "authToken"), None)
        if not token:
            raise ZimbraSoapError("Zimbra authentication returned no auth token")
        self.auth_token = token

    def create_waitset(self, account_ids: list[str]) -> tuple[str, str]:
        if not account_ids:
            raise ValueError("at least one Zimbra account id is required")
        request = ET.Element(f"{{{ADMIN}}}AdminCreateWaitSetRequest", {"defTypes": "m,a"})
        add = ET.SubElement(request, f"{{{ADMIN}}}add")
        for account_id in account_ids:
            ET.SubElement(add, f"{{{ADMIN}}}a", {"id": account_id})
        response = self._post(self.admin_soap_url, request)
        return self._required_attribute(response, "waitSet"), response.attrib.get("seq", "0")

    def wait(self, waitset_id: str, sequence: str, timeout_seconds: int = 60) -> tuple[str, list[str]]:
        request = ET.Element(
            f"{{{ADMIN}}}AdminWaitSetRequest",
            {
                "waitSet": waitset_id,
                "seq": sequence,
                "defTypes": "m,a",
                "block": "1",
                "timeout": str(timeout_seconds),
            },
        )
        response = self._post(self.admin_soap_url, request, timeout=timeout_seconds + 20)
        account_ids = [node.attrib["id"] for node in response if _local_name(node.tag) == "n" and "id" in node.attrib]
        return response.attrib.get("seq", sequence), account_ids

    def destroy_waitset(self, waitset_id: str) -> None:
        request = ET.Element(f"{{{ADMIN}}}AdminDestroyWaitSetRequest", {"waitSet": waitset_id})
        self._post(self.admin_soap_url, request)

    def recent_messages(self, account_id: str, limit: int = 25) -> list[ZimbraMessage]:
        request = ET.Element(
            f"{{{MAIL}}}SearchRequest",
            {"types": "message", "limit": str(limit), "sortBy": "dateDesc", "fetch": "1"},
        )
        ET.SubElement(request, f"{{{MAIL}}}query").text = "in:inbox"
        response = self._post(self.mail_soap_url, request, account_id=account_id)
        messages: list[ZimbraMessage] = []
        for node in response.iter():
            if _local_name(node.tag) != "m" or "id" not in node.attrib:
                continue
            sender_node = next((item for item in node if _local_name(item.tag) == "e" and item.attrib.get("t", "f") == "f"), None)
            sender = ""
            if sender_node is not None:
                sender = sender_node.attrib.get("p") or sender_node.attrib.get("a", "")
            messages.append(
                ZimbraMessage(
                    message_id=node.attrib["id"],
                    received_millis=int(node.attrib.get("d", "0")),
                    sender=sender,
                    subject=_child_text(node, "su"),
                    fragment=_child_text(node, "fr"),
                )
            )
        return messages

    def upcoming_appointments(self, account_id: str, limit: int = 100) -> list[ZimbraAppointment]:
        request = ET.Element(
            f"{{{MAIL}}}SearchRequest",
            {"types": "appointment", "limit": str(limit), "sortBy": "dateAsc", "calExpandInstStart": str(int(time.time() * 1000)), "calExpandInstEnd": str(int((time.time() + 14 * 86_400) * 1000))},
        )
        ET.SubElement(request, f"{{{MAIL}}}query").text = "in:calendar"
        response = self._post(self.mail_soap_url, request, account_id=account_id)
        appointments: list[ZimbraAppointment] = []
        for node in response.iter():
            if _local_name(node.tag) != "appt" or "id" not in node.attrib:
                continue
            for instance in (item for item in node if _local_name(item.tag) == "inst" and "s" in item.attrib):
                appointments.append(
                    ZimbraAppointment(
                        appointment_id=node.attrib["id"],
                        start_millis=int(instance.attrib["s"]),
                        duration_millis=int(instance.attrib.get("dur", "0")),
                        subject=_child_text(node, "su"),
                        location=_child_text(node, "loc"),
                    )
                )
        return appointments

    def _post(
        self,
        url: str,
        request_element: ET.Element,
        *,
        include_context: bool = True,
        account_id: str | None = None,
        timeout: int = 30,
    ) -> ET.Element:
        envelope = ET.Element(f"{{{SOAP}}}Envelope")
        header = ET.SubElement(envelope, f"{{{SOAP}}}Header")
        if include_context:
            if not self.auth_token:
                raise ZimbraSoapError("Zimbra client is not authenticated")
            context = ET.SubElement(header, f"{{{ZIMBRA}}}context")
            ET.SubElement(context, f"{{{ZIMBRA}}}authToken").text = self.auth_token
            if account_id:
                ET.SubElement(context, f"{{{ZIMBRA}}}account", {"by": "id"}).text = account_id
        body = ET.SubElement(envelope, f"{{{SOAP}}}Body")
        body.append(request_element)
        payload = ET.tostring(envelope, encoding="utf-8", xml_declaration=True)
        request = urllib.request.Request(
            url,
            data=payload,
            headers={"Content-Type": "application/soap+xml; charset=utf-8", "User-Agent": "mission-leben-zimbra-bridge/0.4"},
            method="POST",
        )
        try:
            with urllib.request.urlopen(request, timeout=timeout) as response:
                root = ET.fromstring(response.read())
        except urllib.error.HTTPError as error:
            details = error.read(8192).decode(errors="replace")
            raise ZimbraSoapError(f"Zimbra returned HTTP {error.code}: {html.escape(details[:500])}") from error
        except (urllib.error.URLError, ET.ParseError) as error:
            raise ZimbraSoapError("Zimbra SOAP request failed") from error
        fault = next((node for node in root.iter() if _local_name(node.tag) == "Fault"), None)
        if fault is not None:
            raise ZimbraSoapError("Zimbra SOAP fault: " + " ".join(text.strip() for text in fault.itertext() if text.strip())[:500])
        soap_body = next((node for node in root if _local_name(node.tag) == "Body"), None)
        if soap_body is None or len(soap_body) == 0:
            raise ZimbraSoapError("Zimbra SOAP response contains no body")
        return soap_body[0]

    @staticmethod
    def _required_attribute(element: ET.Element, name: str) -> str:
        value = element.attrib.get(name, "")
        if not value:
            raise ZimbraSoapError(f"Zimbra response is missing {name}")
        return value


def format_appointment_summary(start_millis: int, location: str, timezone_name: str) -> str:
    local = datetime.fromtimestamp(start_millis / 1000, timezone.utc).astimezone(ZoneInfo(timezone_name))
    time_text = local.strftime("%d.%m.%Y, %H:%M Uhr")
    return f"{time_text} · {location}" if location else time_text


def iso_from_millis(value: int) -> str:
    return datetime.fromtimestamp(value / 1000, timezone.utc).isoformat().replace("+00:00", "Z")


def reminder_iso(start_millis: int, minutes_before: int) -> str:
    value = datetime.fromtimestamp(start_millis / 1000, timezone.utc) - timedelta(minutes=minutes_before)
    return value.isoformat().replace("+00:00", "Z")
