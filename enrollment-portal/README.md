# Mission Leben Geräte-Einrichtung

Der Container stellt die bewusst einfache Oberfläche **Gerät einrichten** bereit. Er speichert weder Benutzer noch Geräte. Authentik 2026.8.3 bleibt die alleinige Quelle für Benutzer, `ORG_*`-Gruppen, Endpoint Devices, Device Access Groups, Bindungen, Enrollment-Tokens und Audit-Ereignisse.

## Bedienung

Für Mitarbeiter mit vorhandenem TOTP öffnet die App direkt `/self`. Nach Benutzername, Passwort und TOTP bestätigt die Person nur noch „Gerät jetzt registrieren“. Der Container bindet das Gerät an genau dieses angemeldete Konto und leitet automatisch zur App zurück.

Für eine Einrichtung im Auftrag:

1. Als IT, zentrale Leitung, EL oder PDL die Verwaltungsseite öffnen.
2. Gerätetyp wählen: Mitarbeiter-Handy oder gemeinsames Tablet.
3. Mitarbeiter beziehungsweise Einrichtung auswählen.
4. Den zehn Minuten gültigen QR-Code mit der normalen Kamera des neuen Geräts scannen.

Der QR-Code verwendet einen verifizierten HTTPS-App-Link. Ist Mission Leben Zentral bereits installiert, öffnet Android direkt die App. Andernfalls erscheint eine öffentliche Seite mit genau zwei Schritten: App herunterladen und anschließend Einrichtung fortsetzen. Das Enrollment-Geheimnis steht ausschließlich im URL-Fragment hinter `#`; Browser und Reverse Proxy senden es daher nicht an den Webserver oder in dessen Zugriffsprotokoll.

Die Android-App übernimmt den Gerätemodus aus dem QR-Code. Der öffentliche Redeem-Endpunkt registriert das Gerät direkt bei Authentik und löscht den Enrollment-Token vor der Antwort. Der Container läuft absichtlich mit genau einem Worker; mehrere Replikate benötigen zuerst eine gemeinsam genutzte Sperre.

## Rollen

| Authentik-Gruppe | Bereich |
|---|---|
| `ML_DEVICE_INIT_IT` | organisationsweit sowie Support |
| `ML_DEVICE_INIT_ZENTRALE` | explizit über `ORG_*` zugewiesene Bereiche |
| `ML_DEVICE_INIT_EL` | eigene, über `ORG_*` zugewiesene Einrichtung |
| `ML_DEVICE_INIT_PDL` | eigene, über `ORG_*` zugewiesene Einrichtung |

Es gibt bewusst keine GF-Rolle. Ohne eine der vier Rollen verweigert der Container die Mitarbeitersuche und Shared-Tablet-Einrichtung. Die persönliche Selbstregistrierung bleibt für aktive Mitarbeiter mit vorhandenem TOTP erreichbar. Außer IT benötigt jede berechtigte Leitung mindestens eine gültige `ORG_*`-Mitgliedschaft mit `iam_group_type=organization_house` oder `organization_unit`.

Eine reine Benutzername-/Kennwort-Sitzung reicht nicht aus. Der Bootstrap legt einen anwendungsbezogenen Authentication Flow für Benutzername, Kennwort und TOTP sowie einen eigenen Authorization Flow mit derselben TOTP-Stufe an. Ein zwei Minuten gültiger, stufen- und gerätegebundener Authentik-Cookie verhindert nur direkt nach der frischen Anmeldung eine doppelte Abfrage. Eine ältere Authentik-Sitzung überspringt die TOTP-Prüfung nicht. Personen ohne vorhandenes TOTP werden abgewiesen; die Seite bietet keine MFA-Ersteinrichtung an.

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
- einen eigenen Authentication Flow für Benutzername, Kennwort und vorhandenes TOTP,
- einen anwendungsbezogenen Authorization Flow mit verpflichtendem, bereits vorhandenem TOTP,
- die Bindung an den eingebetteten Proxy-Outpost,
- ein Servicekonto mit ausschließlich den benötigten API-Rechten.

Zusätzlich setzt es beim eingebetteten Outpost `authentik_host` und, sofern
noch leer, `authentik_host_browser` auf `https://id.mission-leben.de`. Beim
eingebetteten Outpost muss `authentik_host` die öffentliche Authentik-Adresse
sein; die interne API-Verbindung verwendet weiterhin Authentiks lokalen
IPC-Pfad. Abweichende Umgebungen können dafür
`ML_ENROLL_AUTHENTIK_BROWSER_ORIGIN` setzen. Bedient der eingebettete Outpost
bereits weitere Provider oder ist die Browser-Adresse abweichend belegt,
bricht das Skript aus Sicherheitsgründen ab, statt globale Einstellungen zu
überschreiben.

Der API-Schlüssel ist absichtlich nicht automatisch ablaufend, weil Authentik einen ablaufenden API-Schlüssel serverseitig rotiert, die gemountete Secret-Datei jedoch nicht aktualisieren kann. Er muss betrieblich regelmäßig kontrolliert rotiert werden. Durch seine eng begrenzten Rechte kann er keine Kennwörter ändern, Benutzer anlegen oder Anwendungen administrieren.

Für die App-Bestätigung legt `authentik/bootstrap_app_approval.py` vorher die Authentik-Duo-Stufe an. Deren ausgegebene `duo_stage_uuid` wird als `ML_ENROLL_APP_APPROVAL_STAGE_UUID` gesetzt. Das Portal legt bei jeder persönlichen Geräteinitialisierung idempotent ein bestätigtes Authentik-DuoDevice an: sowohl bei der TOTP-Selbstregistrierung als auch bei der Einrichtung durch EL, PDL, zentrale Leitung oder IT. Dessen `duo_user_id` entspricht exakt dem stabilen OIDC-Subject des Benutzers. Shared Tablets erhalten kein solches Gerät. Die zusätzlichen Servicekonto-Rechte sind ausschließlich `view_authenticatorduostage` und `add_duodevice`.

Anschließend EL, PDL, zentrale Leitungen und IT ihren jeweiligen Rollengruppen hinzufügen. Zentrale Leitungen, EL und PDL benötigen zusätzlich die zugehörigen bestehenden `ORG_*`-Gruppen. IT benötigt keine `ORG_*`-Mitgliedschaft.

## Container starten

```bash
cd enrollment-portal
cp .env.example .env
install -d -m 0700 secrets
openssl rand -hex 32 > secrets/csrf-secret
chown 10002:10002 secrets/*
chmod 0400 secrets/*
docker compose -f compose.example.yml up -d --build
```

Der numerische Besitzer `10002:10002` entspricht ausschließlich dem nicht privilegierten Benutzer im Container. Dadurch kann der Container die read-only eingebundenen Dateien lesen, während andere Prozesse auf dem Host ausgeschlossen bleiben.

In `.env` muss `ML_ENROLL_AGENT_CONNECTOR_UUID` auf den vorhandenen Connector `Mission Leben Android`, `ML_ENROLL_APP_APPROVAL_STAGE_UUID` auf die ausgegebene App-Bestätigungsstufe und `ML_ENROLL_ANDROID_CERT_SHA256_FINGERPRINTS` auf den SHA-256-Fingerabdruck des endgültigen Android-Produktionszertifikats zeigen. Der Container bindet nur an `127.0.0.1:8081`; extern wird ausschließlich der Reverse Proxy veröffentlicht. `deploy/nginx-forward-auth.conf` ist ein gehärtetes Beispiel für `geraete.mission-leben.de`. Die Installationsseite, ihre beiden statischen Dateien und `/.well-known/assetlinks.json` müssen ohne Authentik-Anmeldung erreichbar sein; alle Verwaltungsseiten bleiben geschützt.

## Tests

```bash
python -m venv .venv
.venv/bin/pip install -e '.[test]'
.venv/bin/pytest -q
podman build -t mission-leben-device-enrollment .
```
