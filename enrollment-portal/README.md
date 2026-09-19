# Mission Leben Geräte-Einrichtung

Der Container stellt die bewusst einfache Oberfläche **Gerät einrichten** bereit. Er speichert weder Benutzer noch Geräte. Authentik 2026.8.3 bleibt die alleinige Quelle für Benutzer, `ORG_*`-Gruppen, Endpoint Devices, Device Access Groups, Bindungen, Enrollment-Tokens und Audit-Ereignisse.

## Bedienung

1. Gerätetyp wählen: Mitarbeiter-Handy oder gemeinsames Tablet.
2. Mitarbeiter beziehungsweise Einrichtung auswählen.
3. Den fünf Minuten gültigen QR-Code mit Mission Leben Zentral scannen.

Die Android-App übernimmt den Gerätemodus aus dem QR-Code. Der öffentliche Redeem-Endpunkt registriert das Gerät direkt bei Authentik und löscht den Enrollment-Token vor der Antwort. Der Container läuft absichtlich mit genau einem Worker; mehrere Replikate benötigen zuerst eine gemeinsam genutzte Sperre.

## Rollen

| Authentik-Gruppe | Bereich |
|---|---|
| `ML_DEVICE_INIT_IT` | organisationsweit sowie Support |
| `ML_DEVICE_INIT_ZENTRALE` | explizit über `ORG_*` zugewiesene Bereiche |
| `ML_DEVICE_INIT_EL` | eigene, über `ORG_*` zugewiesene Einrichtung |
| `ML_DEVICE_INIT_PDL` | eigene, über `ORG_*` zugewiesene Einrichtung |

Es gibt bewusst keine GF-Rolle. Ohne eine der vier Rollen verweigert der Container den Zugriff. Außer IT benötigt jede berechtigte Person mindestens eine gültige `ORG_*`-Mitgliedschaft mit `iam_group_type=organization_house` oder `organization_unit`.

Eine reine Benutzername-/Kennwort-Sitzung reicht auch für diese vier Rollen nicht aus. Der Bootstrap legt für die Anwendung einen eigenen Authorization Flow an, der bei jedem neuen Outpost-Zugang ein bereits eingerichtetes TOTP oder WebAuthn/Passkey verlangt und Personen ohne solchen Faktor abweist. Die Seite bietet keine MFA-Ersteinrichtung an.

## Authentik vorbereiten

Vorher PostgreSQL sichern. Danach `authentik/bootstrap_enrollment_portal.py` in den Authentik-Servercontainer kopieren oder über stdin ausführen. Die einzige Ausgabe ist das API-Token und muss direkt in eine Root-only-Datei geschrieben werden:

```bash
umask 077
podman exec -i authentik-server ak shell \
  < authentik/bootstrap_enrollment_portal.py \
  > enrollment-portal/secrets/authentik-enrollment-api-token
```

Das Skript ist idempotent und erstellt:

- die vier Operatorgruppen,
- die Anwendung `Gerät einrichten`,
- einen Forward-Auth-Proxy-Provider,
- einen anwendungsbezogenen Authorization Flow mit verpflichtendem TOTP oder WebAuthn,
- die Bindung an den eingebetteten Proxy-Outpost,
- ein Servicekonto mit ausschließlich den benötigten API-Rechten.

Der API-Schlüssel ist absichtlich nicht automatisch ablaufend, weil Authentik einen ablaufenden API-Schlüssel serverseitig rotiert, die gemountete Secret-Datei jedoch nicht aktualisieren kann. Er muss betrieblich regelmäßig kontrolliert rotiert werden. Durch seine eng begrenzten Rechte kann er keine Kennwörter ändern, Benutzer anlegen oder Anwendungen administrieren.

Anschließend EL, PDL, zentrale Leitungen und IT ihren jeweiligen Rollengruppen hinzufügen. Zentrale Leitungen, EL und PDL benötigen zusätzlich die zugehörigen bestehenden `ORG_*`-Gruppen. IT benötigt keine `ORG_*`-Mitgliedschaft.

## Container starten

```bash
cd enrollment-portal
cp .env.example .env
install -d -m 0700 secrets
openssl rand -hex 32 > secrets/csrf-secret
chmod 0600 secrets/*
podman compose -f compose.example.yml up -d --build
```

In `.env` muss `ML_ENROLL_AGENT_CONNECTOR_UUID` auf den vorhandenen Connector `Mission Leben Android` zeigen. Der Container bindet nur an `127.0.0.1:8081`; extern wird ausschließlich der Reverse Proxy veröffentlicht. `deploy/nginx-forward-auth.conf` ist ein gehärtetes Beispiel für `geraete.mission-leben.de`.

## Tests

```bash
python -m venv .venv
.venv/bin/pip install -e '.[test]'
.venv/bin/pytest -q
podman build -t mission-leben-device-enrollment .
```
