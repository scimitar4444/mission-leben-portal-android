# Mission Leben Device & Notification Bridge

Die Bridge ist der zweite, kleine Serverdienst zur Android-App. Sie läuft als eigener Container hinter dem vorhandenen Reverse Proxy – nicht im Authentik-Container und nicht auf dem Smartphone.

## Was die „Brücke“ hält

Die Bridge hält **keine dauerhafte Verbindung und keine Zimbra-Sitzung pro Mitarbeiter**.

- Ein Zimbra-Worker hält einen blockierenden SOAP-WaitSet-Kanal zu einem Mailbox-Server.
- Der WaitSet meldet nur: „Bei Konto-ID X hat sich Mail oder Kalender geändert.“
- Erst dann liest der Worker mit seinem technischen Zimbra-Konto die notwendigen Benachrichtigungsfelder und ordnet die Zimbra-Konto-ID über eine lokale Mapping-Datei dem stabilen Authentik-`sub` zu.
- Die Bridge ermittelt daraus die aktuell freigegebenen Geräte dieses Benutzers.
- ntfy erhält ausschließlich `event_id`, Typ und Revision. Absender, Betreff, Terminzeit und Talk-Text gehen nicht durch ntfy.
- Ein persönliches, freigegebenes Android-Gerät holt die Details anschließend mit einer kurzlebigen, signierten Anfrage. Der private Geräteschlüssel bleibt im Android Keystore.
- Beim Öffnen verwendet Zimbra oder Nextcloud weiterhin die echte Authentik-/Benutzersitzung im Webcontainer. Das technische Zimbra-Konto wird niemals an die App weitergereicht.

Ein WaitSet arbeitet serverbezogen. Bei mehreren Zimbra-Mailbox-Servern wird deshalb je Mailbox-Server eine Worker-Instanz mit den dort liegenden Konto-IDs betrieben. Der Zimbra-Vertrag verlangt für die Mehrkonten-Variante ein Admin-Token; deshalb muss das Integrationskonto separat, überwacht und so eng wie in der Zimbra-Version möglich berechtigt werden.

## Klare Verantwortungsgrenze

Authentik Endpoint Devices ist trotz Early Preview die einzige Gerätedatenbank. Enrollment, Device, Connection, Device Token, Fakten, Ablauf und Device Access Group liegen in Authentik. Die Bridge besitzt keine Gerätefreigabe und keine Enrollment-Codes.

Sie speichert nur die für Kommunikation notwendige Zuordnung eines von Authentik live bestätigten Geräts: Authentik-Subject, verschlüsseltes ntfy-Lese-/Schreibtoken, zufälliges Topic, verschlüsseltes Authentik-Device-Token, öffentlichen P-256-Kommunikationsschlüssel und Datenschutzmodus. Das Device Token wird vor Registrierung, Zustellung und Detailabruf live über den zustandslosen Statusendpunkt des Geräteportals geprüft. Dieser validiert Token, Ablauf und Deaktivierungsstatus unmittelbar gegen Authentik; das Portal führt keine zweite Gerätedatenbank.

## Enthaltene Funktionen

- Authentik-UserInfo-Prüfung bei Push-Anmeldung, Abmeldung, Talk-Handoff und Link-Zielen
- Live-Prüfung des Authentik Device Token vor Push-Registrierung, Zustellung und Detailabruf
- verschlüsselte Speicherung von ntfy-Tokens und Authentik Device Token mit AES-256-GCM
- P-256-Signaturprüfung für Detailabrufe, 120-Sekunden-Zeitfenster und Nonce-Wiederholungsschutz
- drei Datenschutzstufen: `minimal`, `standard`, `detailed`; Shared Tablets erzwingen `minimal`
- HMAC-signierter Normaleingang sowie ein nativer Nextcloud-Talk-Bot-Webhook
- eigener ntfy-Container mit `deny-all`, getrennten Lese-/Schreibrechten und Nachrichten ohne vertrauliche Inhalte
- indizierte Zustellwarteschlange für Kalenderhinweise mit 5, 10, 15 oder 30 Minuten Vorlauf pro persönlichem Gerät; Shared Tablets verwenden 15 Minuten
- deduplizierte Quellereignisse und Versandstatus pro Gerät
- Zimbra SOAP WaitSet für ausgewählte Konten, Mail-Suche und Kalenderinstanzen
- Talk-Handoff-Vertrag mit fest konfigurierten Zielgeräten und maximal 30 Sekunden TTL
- Authentik-App-Bestätigung über die normale Duo-Stufe und eine eng begrenzte, signierte Duo-Auth-API-Kompatibilität; die eigentliche Bestätigung kommt ausschließlich von einem persönlichen, live bei Authentik geprüften Endpoint

## Noch kein Produktionsversprechen

Der Container ist eine überprüfbare Pilotimplementierung. Vor einem Live-Rollout müssen in der tatsächlichen Umgebung besonders diese Punkte geprüft werden:

1. Zimbra-10.1-Rechte des dedizierten Integrationskontos und Routing je Mailbox-Server.
2. Serien, Ausnahmen, Absagen und individuelle Erinnerungszeiten der realen Zimbra-Kalender.
3. Talk-Bot-Zuordnungen und Signaturen gegen die tatsächlich installierte Nextcloud-/Talk-Version sowie die gepflegte Raum-Teilnehmer-Zuordnung.
4. Mitarbeiter-Offboarding beginnt ausschließlich mit der Kontodeaktivierung in Authentik. Der fünfminütige Reconciler deaktiviert zugeordnete persönliche Geräte, entzieht Authentik-Sitzungen und -Tokens und ruft `mission-leben-bridge-offboard` für das stabile Subject auf. Vor ihrer Datenbereinigung sendet die Bridge über ntfy nur `refresh_security_state`; anschließend werden beide ntfy-Geräteidentitäten widerrufen. Die App prüft Authentik selbst und löscht bei gesperrtem Endpoint die lokale Sitzung und Webdaten. Danach löscht die Bridge Kommunikationsregistrierungen, Nonces, Benachrichtigungsinhalte, Anmeldeanfragen und Talk-Übergaben; nur ein inaktiver Subject-Tombstone ohne Name oder E-Mail bleibt als Sperre. Für garantiertes Fernlöschen auf ausgeschalteten Geräten bleibt MDM/Android Work Profile notwendig.
5. SQLite ist für einen einzelnen Pilotcontainer vorgesehen. Vor horizontaler Skalierung muss der Store auf PostgreSQL und eine gemeinsame Job-Queue umgestellt werden.

## Start als Pilot

Für den Kommunikations-Pilot steht eine reduzierte Compose-Datei mit Bridge, ntfy und einem optionalen Zimbra-Worker bereit. Sie erzeugt beim ersten Start die lokalen Basissecrets, bindet Bridge und ntfy ausschließlich an Loopback und legt ihre SQLite-Datenbanken persistent ab. Der Zimbra-Worker startet erst nach vollständiger Konfiguration ausdrücklich durch den Administrator:

```bash
sudo ./deploy/install-device-pilot.sh
```

Nach erfolgreich bestandener Vorprüfung werden die zusätzlichen Quellen gezielt gestartet:

```bash
docker compose --project-name mission-leben-device -f compose.device-pilot.yml --profile tools run --rm preflight
docker compose --project-name mission-leben-device -f compose.device-pilot.yml up -d --build bridge zimbra-worker
```

Die Nginx-Locations aus `deploy/nginx-device-bridge-location.conf` veröffentlichen `/device-bridge/healthz`, `/device-bridge/v1/` sowie exakt die drei von Authentik signierten Routen `/auth/v2/ping`, `/auth/v2/check` und `/auth/v2/auth`. Admin-, interne und Quellendpunkte bleiben von außen gesperrt. Der tägliche konsistente SQLite-Backupjob wird mit den beiden mitgelieferten systemd-Units aktiviert.

## Nextcloud-Ankündigungen

Die kleine Nextcloud-App unter `nextcloud-app/missionleben_announcements` stellt ausschließlich einen HMAC-signierten Leseendpunkt bereit. Die Bridge sendet die aus dem live geprüften Authentik-Token stammende Benutzerkennung beziehungsweise E-Mail. Nextcloud löst genau ein aktives Konto auf und filtert die Einträge aus dem Announcement Center anhand der aktuellen Nextcloud-Gruppen des Benutzers. Gruppenbezeichnungen werden weder an die Bridge noch an Android ausgegeben.

Der Bridge-Cache ist nach Authentik-Subject getrennt. Ein Abruf prüft immer zuerst das Access-Token bei Authentik; der Cache ist also kein Ersatz für Benutzer- oder Sperrprüfung. Ein Ergebnis gilt fünf Minuten als frisch. Ist Nextcloud danach nicht erreichbar, darf die Bridge es höchstens 24 Stunden mit `stale=true` ausliefern. Beim Offboarding wird auch dieser Cache gelöscht.

```dotenv
BRIDGE_NEXTCLOUD_ANNOUNCEMENTS_URL=https://nextcloud.mission-leben.de
BRIDGE_NEXTCLOUD_ANNOUNCEMENTS_SECRET_FILE=/run/secrets/nextcloud-announcements-secret
BRIDGE_ANNOUNCEMENT_CACHE_TTL_SECONDS=300
BRIDGE_ANNOUNCEMENT_STALE_TTL_SECONDS=86400
```

Das Secret ist ein eigener zufälliger Wert mit mindestens 32 Zeichen und wird im Container nur als schreibgeschützte Datei eingebunden. Für den Pilot muss die Vorprüfung `announcements` als Pflichtkomponente enthalten. Ein Authentik-Clusterwechsel benötigt keine Codeänderung, solange die öffentliche Issuer-/UserInfo-Adresse stabil bleibt; Authentik bleibt vor jedem Cachezugriff erreichbar und autoritativ.

Für die App-Bestätigung müssen `BRIDGE_DUO_INTEGRATION_KEY`, `BRIDGE_DUO_SECRET_KEY` und `BRIDGE_DUO_API_HOSTNAME` mit der Authentik-Stufe aus `authentik/bootstrap_app_approval.py` übereinstimmen. Das API-Geheimnis wird nie an Android übertragen. `/auth/v2/auth` wartet höchstens 60 Sekunden; bei Zeitablauf, Bridge-Fehler oder unbekanntem/gesperrtem Endpoint liefert die Bridge ausdrücklich `deny`.

Für den vollständigen Benachrichtigungspilot mit ntfy, Zimbra und Talk gilt:

```bash
cd bridge
cp .env.example .env
mkdir -p data secrets
cp secrets/zimbra-account-map.example.json secrets/zimbra-account-map.json
docker compose -f compose.example.yml --profile tools run --rm preflight
docker compose -f compose.example.yml up --build -d bridge zimbra-worker
```

Die Vorprüfung beendet sich nur dann erfolgreich, wenn Bridge-Grundschutz, ntfy-Dateien, Zimbra-Dateien und Talk-Zuordnungen vollständig und syntaktisch plausibel sind. Sie gibt ausschließlich Status und Fehlerursachen aus, niemals Kennwörter, Schlüssel oder Token. Der laufende Geräte-Pilot kann ohne optionale Quellen geprüft werden mit:

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

Beim Ausscheiden wird das Benutzerkonto in Authentik deaktiviert. Geräte- und Bindungsdatensätze bleiben für die Nachverfolgbarkeit bestehen und erhalten den Status `disabled`; sie werden nicht gelöscht. Beim reinen Geräteverlust wird nur das betroffene Gerät in Authentik gesperrt. Die Bridge besitzt weiterhin keinen eigenen Freigabestatus: Ihr inaktiver Subject-Tombstone verhindert lediglich das erneute Speichern nachlaufender Kommunikationsereignisse.

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
