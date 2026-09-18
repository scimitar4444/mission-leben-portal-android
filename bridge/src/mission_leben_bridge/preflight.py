from __future__ import annotations

import argparse
import base64
import json
import os
import re
from pathlib import Path
from typing import Any, Mapping
from urllib.parse import urlsplit
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

from cryptography.hazmat.primitives import serialization


COMPONENTS = ("bridge", "fcm", "zimbra", "talk")
ROOM_TOKEN = re.compile(r"^[A-Za-z0-9_-]{6,128}$")


def _value(environment: Mapping[str, str], name: str) -> str:
    return str(environment.get(name, "")).strip()


def _valid_url(value: str, *, https_only: bool) -> bool:
    try:
        parsed = urlsplit(value)
        return bool(parsed.hostname and parsed.scheme in ({"https"} if https_only else {"http", "https"}))
    except ValueError:
        return False


def _json_object(value: str) -> dict[str, Any] | None:
    try:
        parsed = json.loads(value)
    except (json.JSONDecodeError, TypeError):
        return None
    return parsed if isinstance(parsed, dict) else None


def _read_json_object(path_value: str) -> tuple[dict[str, Any] | None, str | None]:
    if not path_value:
        return None, "Dateipfad fehlt"
    path = Path(path_value)
    if not path.is_file():
        return None, "Datei fehlt oder ist keine reguläre Datei"
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError):
        return None, "Datei ist nicht lesbar oder enthält kein gültiges JSON"
    if not isinstance(value, dict):
        return None, "JSON-Wurzel muss ein Objekt sein"
    return value, None


def _report(status: str, issues: list[str]) -> dict[str, Any]:
    return {"status": status, "issues": issues}


def check_bridge(environment: Mapping[str, str]) -> dict[str, Any]:
    issues: list[str] = []
    hmac_secret = _value(environment, "BRIDGE_INTERNAL_HMAC_SECRET")
    if len(hmac_secret) < 32:
        issues.append("BRIDGE_INTERNAL_HMAC_SECRET fehlt oder ist kürzer als 32 Zeichen")

    data_key_value = _value(environment, "BRIDGE_DATA_KEY")
    try:
        data_key = base64.urlsafe_b64decode(data_key_value + "=" * (-len(data_key_value) % 4))
    except (ValueError, TypeError):
        data_key = b""
    if len(data_key) != 32:
        issues.append("BRIDGE_DATA_KEY muss als Base64url exakt 32 Bytes ergeben")

    for name in ("BRIDGE_AUTHENTIK_USERINFO_URL", "BRIDGE_AUTHENTIK_AGENT_CONFIG_URL"):
        if not _valid_url(_value(environment, name), https_only=True):
            issues.append(f"{name} muss eine gültige HTTPS-URL sein")

    database_path = _value(environment, "BRIDGE_DATABASE_PATH")
    if not database_path or not Path(database_path).is_absolute():
        issues.append("BRIDGE_DATABASE_PATH muss ein absoluter Pfad sein")
    return _report("invalid" if issues else "ready", issues)


def check_fcm(environment: Mapping[str, str]) -> dict[str, Any]:
    project_id = _value(environment, "BRIDGE_FIREBASE_PROJECT_ID")
    credentials_path = _value(environment, "GOOGLE_APPLICATION_CREDENTIALS")
    if not project_id and not credentials_path:
        return _report("disabled", [])

    issues: list[str] = []
    if not project_id:
        issues.append("BRIDGE_FIREBASE_PROJECT_ID fehlt")
    credentials, error = _read_json_object(credentials_path)
    if error:
        issues.append(f"Firebase-Dienstkonto: {error}")
    elif credentials is not None:
        if credentials.get("type") != "service_account":
            issues.append("Firebase-Dienstkonto hat nicht den Typ service_account")
        if project_id and credentials.get("project_id") != project_id:
            issues.append("Firebase-Projekt-ID und Dienstkonto stimmen nicht überein")
        if not str(credentials.get("client_email", "")).strip():
            issues.append("Firebase-Dienstkonto enthält keine client_email")
        private_key = str(credentials.get("private_key", ""))
        try:
            serialization.load_pem_private_key(private_key.encode(), password=None)
        except (ValueError, TypeError):
            issues.append("Firebase-Dienstkonto enthält keinen gültigen privaten Schlüssel")
    return _report("invalid" if issues else "ready", issues)


def check_talk(environment: Mapping[str, str]) -> dict[str, Any]:
    backend = _value(environment, "BRIDGE_NEXTCLOUD_BACKEND_URL").rstrip("/")
    secret_file = _value(environment, "BRIDGE_NEXTCLOUD_TALK_SECRET_FILE")
    inline_secret = _value(environment, "BRIDGE_NEXTCLOUD_TALK_SECRET")
    configured_recipients = _value(environment, "BRIDGE_TALK_RECIPIENTS_JSON")
    configured_users = _value(environment, "BRIDGE_NEXTCLOUD_USER_SUBJECTS_JSON")
    recipients_raw = configured_recipients or "{}"
    users_raw = configured_users or "{}"
    recipients = _json_object(recipients_raw)
    users = _json_object(users_raw)
    mappings_configured = configured_recipients not in {"", "{}"} or configured_users not in {"", "{}"}
    if not backend and not secret_file and not inline_secret and not mappings_configured:
        return _report("disabled", [])

    issues: list[str] = []
    if not _valid_url(backend, https_only=True):
        issues.append("BRIDGE_NEXTCLOUD_BACKEND_URL muss eine gültige HTTPS-URL sein")

    secret = inline_secret
    if secret_file:
        path = Path(secret_file)
        if not path.is_file():
            issues.append("Nextcloud-Talk-Secret-Datei fehlt")
        else:
            try:
                secret = path.read_text(encoding="utf-8").strip()
            except (OSError, UnicodeError):
                issues.append("Nextcloud-Talk-Secret-Datei ist nicht lesbar")
    if len(secret) < 32:
        issues.append("Nextcloud-Talk-Secret fehlt oder ist kürzer als 32 Zeichen")

    if recipients is None:
        issues.append("BRIDGE_TALK_RECIPIENTS_JSON ist kein gültiges JSON-Objekt")
    elif not recipients:
        issues.append("BRIDGE_TALK_RECIPIENTS_JSON enthält keine Räume")
    else:
        for room, subjects in recipients.items():
            if not ROOM_TOKEN.fullmatch(str(room)):
                issues.append("Talk-Empfängerzuordnung enthält einen ungültigen Raumtoken")
                break
            if not isinstance(subjects, list) or not subjects or any(not str(value).strip() for value in subjects):
                issues.append("Jeder Talk-Raum benötigt mindestens ein gültiges Authentik-Subject")
                break

    if users is None:
        issues.append("BRIDGE_NEXTCLOUD_USER_SUBJECTS_JSON ist kein gültiges JSON-Objekt")
    elif not users or any(not str(user).strip() or not str(subject).strip() for user, subject in users.items()):
        issues.append("Nextcloud-Benutzer müssen Authentik-Subjects zugeordnet sein")
    return _report("invalid" if issues else "ready", issues)


def check_zimbra(environment: Mapping[str, str]) -> dict[str, Any]:
    names = (
        "ZIMBRA_ADMIN_SOAP_URL",
        "ZIMBRA_MAIL_SOAP_URL",
        "ZIMBRA_ADMIN_USER",
        "ZIMBRA_ADMIN_PASSWORD_FILE",
        "ZIMBRA_ACCOUNT_MAP_FILE",
    )
    if not any(_value(environment, name) for name in names):
        return _report("disabled", [])

    issues: list[str] = []
    for name in ("ZIMBRA_ADMIN_SOAP_URL", "ZIMBRA_MAIL_SOAP_URL"):
        if not _valid_url(_value(environment, name), https_only=True):
            issues.append(f"{name} muss eine gültige HTTPS-URL sein")
    if not _value(environment, "ZIMBRA_ADMIN_USER"):
        issues.append("ZIMBRA_ADMIN_USER fehlt")
    if _value(environment, "ZIMBRA_ADMIN_PASSWORD"):
        issues.append("Zimbra-Kennwort darf nicht als Umgebungsvariable gesetzt sein")

    password_path = _value(environment, "ZIMBRA_ADMIN_PASSWORD_FILE")
    if not password_path or not Path(password_path).is_file():
        issues.append("Zimbra-Kennwortdatei fehlt")
    else:
        try:
            if not Path(password_path).read_text(encoding="utf-8").strip():
                issues.append("Zimbra-Kennwortdatei ist leer")
        except (OSError, UnicodeError):
            issues.append("Zimbra-Kennwortdatei ist nicht lesbar")

    account_map, error = _read_json_object(_value(environment, "ZIMBRA_ACCOUNT_MAP_FILE"))
    if error:
        issues.append(f"Zimbra-Kontozuordnung: {error}")
    elif account_map is not None:
        if not account_map:
            issues.append("Zimbra-Kontozuordnung enthält keine Konten")
        for account_id, mapping in account_map.items():
            if not str(account_id).strip() or not isinstance(mapping, dict) or not str(mapping.get("subject", "")).strip():
                issues.append("Jedes Zimbra-Konto benötigt ein gültiges Authentik-Subject")
                break

    if not _valid_url(_value(environment, "BRIDGE_BASE_URL"), https_only=False):
        issues.append("BRIDGE_BASE_URL muss eine gültige HTTP- oder HTTPS-URL sein")
    if len(_value(environment, "BRIDGE_INTERNAL_HMAC_SECRET")) < 32:
        issues.append("Zimbra-Worker benötigt dasselbe mindestens 32 Zeichen lange Bridge-HMAC-Secret")
    try:
        minutes = int(_value(environment, "ZIMBRA_REMINDER_MINUTES") or "15")
        if not 0 <= minutes <= 1440:
            raise ValueError
    except ValueError:
        issues.append("ZIMBRA_REMINDER_MINUTES muss zwischen 0 und 1440 liegen")
    try:
        ZoneInfo(_value(environment, "ZIMBRA_TIMEZONE") or "Europe/Berlin")
    except ZoneInfoNotFoundError:
        issues.append("ZIMBRA_TIMEZONE ist unbekannt")
    return _report("invalid" if issues else "ready", issues)


def evaluate(environment: Mapping[str, str], required: set[str] | None = None) -> dict[str, Any]:
    required_components = {"bridge"} | set(required or ())
    unknown = required_components - set(COMPONENTS)
    if unknown:
        raise ValueError("unknown required component")
    components = {
        "bridge": check_bridge(environment),
        "fcm": check_fcm(environment),
        "zimbra": check_zimbra(environment),
        "talk": check_talk(environment),
    }
    ready = all(report["status"] != "invalid" for report in components.values()) and all(
        components[name]["status"] == "ready" for name in required_components
    )
    return {
        "status": "ready" if ready else "not_ready",
        "required": sorted(required_components),
        "components": components,
    }


def _load_env_file(path: Path) -> dict[str, str]:
    result: dict[str, str] = {}
    for raw_line in path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        name, value = line.split("=", 1)
        name = name.strip()
        value = value.strip()
        if len(value) >= 2 and value[0] == value[-1] and value[0] in {"'", '"'}:
            value = value[1:-1]
        result[name] = value
    return result


def main() -> None:
    parser = argparse.ArgumentParser(description="Prüft die Bridge-Konfiguration, ohne Geheimnisse auszugeben.")
    parser.add_argument("--env-file", type=Path, help="Optionale dotenv-Datei; ihre Werte überschreiben die Umgebung.")
    parser.add_argument(
        "--require",
        default="",
        help="Kommagetrennte Pflichtkomponenten: fcm,zimbra,talk (bridge ist immer Pflicht).",
    )
    arguments = parser.parse_args()
    environment = dict(os.environ)
    if arguments.env_file:
        environment.update(_load_env_file(arguments.env_file))
    required = {value.strip() for value in arguments.require.split(",") if value.strip()}
    try:
        result = evaluate(environment, required)
    except (OSError, UnicodeError, ValueError) as error:
        result = {"status": "not_ready", "error": str(error)}
    print(json.dumps(result, ensure_ascii=False, sort_keys=True))
    raise SystemExit(0 if result.get("status") == "ready" else 2)


if __name__ == "__main__":
    main()
