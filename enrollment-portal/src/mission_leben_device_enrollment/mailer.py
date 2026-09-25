from __future__ import annotations

import asyncio
import re
import smtplib
import ssl
from email.message import EmailMessage

from .config import Settings


ADDRESS = re.compile(r"[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}")


def validate_address(value: str, *, corporate_only: bool = False) -> str:
    address = value.strip()
    if len(address) > 254 or not ADDRESS.fullmatch(address):
        raise ValueError("Keine gültige E-Mail-Adresse vorhanden.")
    if corporate_only and address.rsplit("@", 1)[1].casefold() != "mission-leben.de":
        raise ValueError("Bitte eine dienstliche Adresse @mission-leben.de eingeben.")
    return address


async def send_setup_mail(
    settings: Settings,
    recipient: str,
    subject: str,
    body: str,
) -> None:
    if not settings.smtp_host or not settings.smtp_sender:
        raise RuntimeError("Der E-Mail-Versand ist noch nicht konfiguriert.")
    recipient = validate_address(recipient)
    sender = validate_address(settings.smtp_sender)
    message = EmailMessage()
    message["From"] = f"Mission Leben IT-Service <{sender}>"
    message["To"] = recipient
    message["Subject"] = subject
    message.set_content(body)

    def send() -> None:
        with smtplib.SMTP(settings.smtp_host, settings.smtp_port, timeout=10) as smtp:
            smtp.ehlo()
            if not smtp.has_extn("starttls"):
                raise RuntimeError("Der Mailserver bietet kein STARTTLS an.")
            smtp.starttls(context=ssl.create_default_context())
            smtp.ehlo()
            smtp.send_message(message)

    await asyncio.to_thread(send)
