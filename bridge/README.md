# Mission Leben Device & Notification Bridge

Die Bridge ist der zweite, kleine Serverdienst zur Android-App. Sie läuft als eigener Container hinter dem vorhandenen Reverse Proxy – nicht im Authentik-Container und nicht auf dem Smartphone.

## Was die „Brücke“ hält

Die Bridge hält **keine dauerhafte Verbindung und keine Zimbra-Sitzung pro Mitarbeiter**.

- Ein Zimbra-Worker hält einen blockierenden SOAP-WaitSet-Kanal zu einem Mailbox-Server.
- Der WaitSet meldet nur: „Bei Konto-ID X hat sich Mail oder Kalender geändert.“
- Erst dann liest der Worker mit seinem technischen Zimbra-Konto die notwendigen Benachrichtigungsfelder und ordnet die Zimbra-Konto-ID über eine lokale Mapping-Datei dem stabilen Authentik-`sub` zu.
- Die Bridge ermittelt daraus die aktuell freigegebenen Geräte dieses Benutzers.
- FCM erhält ausschließlich `event_id`, Typ und Revision. Absender, Betreff, Terminzeit und Talk-Text gehen nicht durch FCM.
- Ein persönliches, freigegebenes Android-Gerät holt die Details anschließend mit einer kurzlebigen, signierten Anfrage. Der private Geräteschlüssel bleibt im Android Keystore.
- Beim Öffnen verwendet Zimbra oder Nextcloud weiterhin die echte Authentik-/Benutzersitzung im Webcontainer. Das technische Zimbra-Konto wird niemals an die App weitergereicht.

Ein WaitSet arbeitet serverbezogen. Bei mehreren Zimbra-Mailbox-Servern wird deshalb je Mailbox-Server eine Worker-Instanz mit den dort liegenden Konto-IDs betrieben. Der Zimbra-Vertrag verlangt für die Mehrkonten-Variante ein Admin-Token; deshalb muss das Integrationskonto separat, überwacht und so eng wie in der Zimbra-Version möglich berechtigt werden.

## Klare Verantwortungsgrenze

Authentik Endpoint Devices ist trotz Early Preview die einzige Gerätedatenbank. Enrollment, Device, Connection, Device Token, Fakten, Ablauf und Device Access Group liegen in Authentik. Die Bridge besitzt keine Gerätefreigabe und keine Enrollment-Codes.

Sie speichert nur die für Kommunikation notwendige Zuordnung eines von Authentik live bestätigten Geräts: Authentik-Subject, verschlüsselte Firebase-Installations-ID, verschlüsseltes Authentik-Device-Token, öffentlichen P-256-Kommunikationsschlüssel und Datenschutzmodus. Das Device Token wird vor Registrierung, Zustellung und Detailabruf live über Authentik `agent_config` geprüft.

## Enthaltene Funktionen

- Authentik-UserInfo-Prüfung bei Push-Anmeldung, Abmeldung, Talk-Handoff und Link-Zielen
- Live-Prüfung des Authentik Device Token vor Push-Registrierung, Zustellung und Detailabruf
- verschlüsselte Speicherung von Firebase-Installations-ID und Authentik Device Token mit AES-256-GCM
- P-256-Signaturprüfung für Detailabrufe, 120-Sekunden-Zeitfenster und Nonce-Wiederholungsschutz
- drei Datenschutzstufen: `minimal`, `standard`, `detailed`; Shared Tablets erzwingen `minimal`
- HMAC-signierter Normaleingang sowie ein nativer Nextcloud-Talk-Bot-Webhook
- FCM HTTP v1 mit OAuth-Servicekonto; Data Message ohne vertrauliche Inhalte
- terminierter Versand von Kalenderhinweisen, standardmäßig durch den Zimbra-Worker 15 Minuten vor Beginn
- deduplizierte Quellereignisse und Versandstatus pro Gerät
- Zimbra SOAP WaitSet für ausgewählte Konten, Mail-Suche und Kalenderinstanzen
- Talk-Handoff-Vertrag mit fest konfigurierten Zielgeräten und maximal 30 Sekunden TTL

## Noch kein Produktionsversprechen

Der Container ist eine überprüfbare Pilotimplementierung. Vor einem Live-Rollout müssen in der tatsächlichen Umgebung besonders diese Punkte geprüft werden:

1. Zimbra-10.1-Rechte des dedizierten Integrationskontos und Routing je Mailbox-Server.
2. Serien, Ausnahmen, Absagen und individuelle Erinnerungszeiten der realen Zimbra-Kalender.
3. Talk-Bot-Zuordnungen und Signaturen gegen die tatsächlich installierte Nextcloud-/Talk-Version sowie die gepflegte Raum-Teilnehmer-Zuordnung.
4. Geräte-Offboarding erfolgt in Authentik; die Bridge entfernt eine Kommunikationszuordnung, sobald Authentik das Device Token dauerhaft ablehnt. Für garantiertes Fernlöschen auf ausgeschalteten Geräten bleibt MDM/Android Work Profile notwendig.
5. SQLite ist für einen einzelnen Pilotcontainer vorgesehen. Vor horizontaler Skalierung muss der Store auf PostgreSQL und eine gemeinsame Job-Queue umgestellt werden.

## Start als Pilot

Für den ersten Kommunikations-Pilot ohne FCM, Zimbra und Talk steht eine reduzierte Compose-Datei bereit. Sie erzeugt beim ersten Start die Secrets lokal, bindet den Dienst ausschließlich an `127.0.0.1:8080` und legt die SQLite-Datenbank persistent unter `data/bridge.sqlite3` an:

```bash
sudo ./deploy/install-device-pilot.sh
```

Die Nginx-Locations aus `deploy/nginx-device-bridge-location.conf` veröffentlichen nur `/device-bridge/healthz` und `/device-bridge/v1/`. Admin-, interne und Quellendpunkte bleiben von außen gesperrt. Der tägliche konsistente SQLite-Backupjob wird mit den beiden mitgelieferten systemd-Units aktiviert.

Für den vollständigen Benachrichtigungspilot mit FCM, Zimbra und Talk gilt weiterhin:

```bash
cd bridge
cp .env.example .env
mkdir -p data secrets
cp secrets/zimbra-account-map.example.json secrets/zimbra-account-map.json
docker compose -f compose.example.yml --profile tools run --rm preflight
docker compose -f compose.example.yml up --build -d bridge zimbra-worker
```

Die Vorprüfung beendet sich nur dann erfolgreich, wenn Bridge-Grundschutz, Firebase-Dienstkonto, Zimbra-Dateien und Talk-Zuordnungen vollständig und syntaktisch plausibel sind. Sie gibt ausschließlich Status und Fehlerursachen aus, niemals Kennwörter, Schlüssel oder Token. Der laufende Geräte-Pilot kann ohne optionale Quellen geprüft werden mit:

```bash
docker compose -f compose.device-pilot.yml run --rm bridge mission-leben-bridge-preflight
```

`bridge/.env`, `bridge/data/` und echte Dateien unter `bridge/secrets/` werden von Git ignoriert. Der HTTP-Port ist im Beispiel nur an `127.0.0.1` gebunden. TLS und öffentliche Erreichbarkeit übernimmt der bestehende Reverse Proxy, zum Beispiel unter `https://device.mission-leben.de`.

Der Datenordner muss für UID/GID `10001` schreibbar sein:

```bash
sudo chown 10001:10001 bridge/data
```

### Gerät aufnehmen oder sperren

Der Enrollment-Token wird in Authentik erzeugt und einer Device Access Group zugeordnet. Die Android-App enrollt direkt am Authentik Agent Connector. Das Bootstrap- und das Pilot-Token-Skript liegen unter `authentik/`; die genaue Konfiguration steht in `docs/AUTHENTIK_SETUP.md`.

Beim Ausscheiden oder Geräteverlust wird das Gerät in Authentik unter **Endpoint Devices → Devices** ablaufen gelassen oder gelöscht. Die Bridge besitzt absichtlich keinen parallelen Freigabestatus.

## Zimbra-Zuordnung

Die Mapping-Datei enthält keine Kennwörter. Sie ordnet nur Zimbra-Konto-ID zu Authentik-Subject:

```json
{
  "zimbra-account-uuid": {
    "subject": "stabiler-oidc-sub",
    "email": "mitarbeiter@example.org"
  }
}
```

Das Zimbra-Kennwort wird als gemountete Secret-Datei gelesen. Es darf weder in `.env` noch in Git abgelegt werden.

## Nextcloud-Talk-Bot

Der Container implementiert `POST /sources/nextcloud-talk` nach dem offiziellen Talk-Bot-Webhook-Vertrag. Er prüft:

- `X-Nextcloud-Talk-Signature = HMAC-SHA256(random + body, bot_secret)`,
- `X-Nextcloud-Talk-Random` einschließlich Wiederholungsschutz,
- den exakt erwarteten `X-Nextcloud-Talk-Backend`,
- Activity-Streams-Typ `Create` und Objekt-Typ `Note`.

In `BRIDGE_TALK_RECIPIENTS_JSON` wird je Talk-Raumtoken die Liste der Authentik-Subjects gepflegt. `BRIDGE_NEXTCLOUD_USER_SUBJECTS_JSON` ordnet Nextcloud-Benutzer-IDs zu Authentik-Subjects, damit der Absender nicht selbst benachrichtigt wird. Der Bot muss in den relevanten Talk-Räumen mit Webhook-Funktion aktiviert sein. Damit ist keine Änderung am häufig aktualisierten Talk-JavaScript nötig.

Beispiel:

```dotenv
BRIDGE_TALK_RECIPIENTS_JSON={"roomToken":["subject-a","subject-b"]}
BRIDGE_NEXTCLOUD_USER_SUBJECTS_JSON={"nextcloud-user":"subject-a"}
```

Die Talk-Bot-Secret-Datei wird nur in den Bridge-Container gemountet. Sie gehört nicht in `.env` oder Git.

## Alternativer normalisierter Nextcloud-/Talk-Eingang

Ein anderer interner Adapter kann weiterhin nach HMAC-Prüfung beispielsweise senden:

```json
{
  "source_event_id": "talk:room-token:message-id",
  "user_subject": "stabiler-oidc-sub",
  "event_type": "open_talk",
  "title": "Max Mustermann",
  "summary": "Team IT",
  "preview": "Kurze optionale Vorschau",
  "expires_at": "2026-09-19T10:00:00Z"
}
```

Header:

- `X-ML-Source: nextcloud`
- `X-ML-Timestamp: <Unix-Sekunden>`
- `X-ML-Signature: base64url(HMAC-SHA256(secret, canonical_request))`

Die kanonische Form ist in `security.py` definiert und durch Tests abgesichert.

## Tests

```bash
PYTHONPATH=bridge/src python -m unittest discover -s bridge/tests -v
```
