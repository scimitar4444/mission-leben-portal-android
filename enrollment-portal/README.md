# Mission Leben Geräte-Einrichtung

Der Container stellt die bewusst einfache Oberfläche **Gerät einrichten** bereit. Er speichert weder Benutzer noch Geräte. Authentik 2026.8.3 bleibt die alleinige Quelle für Benutzer, `ORG_*`-Gruppen, Endpoint Devices, Device Access Groups, Bindungen, Enrollment-Tokens und Audit-Ereignisse.

## Bedienung

Für Mitarbeiter mit vorhandenem TOTP, Passkey oder bereits registrierter App-Bestätigung öffnet die App direkt `/self`. Nach Benutzername, Passwort und dem gewählten Faktor bestätigt die Person nur noch „Gerät jetzt registrieren“. Der Container bindet das Gerät an genau dieses angemeldete Konto und leitet automatisch zur App zurück. Beim allerersten persönlichen Gerät steht noch keine App-Bestätigung zur Verfügung; dafür bleibt TOTP oder Passkey erforderlich.

Für eine Einrichtung im Auftrag:

1. Als IT, zentrale Leitung, EL oder PDL die Verwaltungsseite öffnen.
2. Gerätetyp wählen: Mitarbeiter-Handy oder gemeinsames Tablet.
3. Mitarbeiter beziehungsweise Einrichtung auswählen.
4. Den zehn Minuten gültigen QR-Code mit der normalen Kamera des neuen Geräts scannen.

Der QR-Code verwendet einen verifizierten HTTPS-App-Link. Ist Mission Leben Zentral bereits installiert, öffnet Android direkt die App. Andernfalls erscheint eine öffentliche Installationsseite mit getrennten Anleitungen für Android und Samsung Android. Die Samsung-Anleitung berücksichtigt sowohl „Apps aus dieser Quelle zulassen“ als auch die zusätzliche „Automatische Sperre“. Da die Sperre direkte APK-Updates ebenfalls blockiert, muss sie für den eigenen OTA-Updateweg ausgeschaltet bleiben; eine von One UI angebotene automatische Reaktivierung nach 30 Minuten ist abzulehnen. Aus Gründen der einfachen Bedienung verlangt die Anleitung keine nachträgliche Rücknahme der Browser-Freigabe. Auch der allgemeine Download-QR-Code auf der Startseite führt zuerst zu dieser Anleitung. Das Enrollment-Geheimnis steht ausschließlich im URL-Fragment hinter `#`; Browser und Reverse Proxy senden es daher nicht an den Webserver oder in dessen Zugriffsprotokoll.

Die Android-App übernimmt den Gerätemodus aus dem QR-Code. Der öffentliche Redeem-Endpunkt registriert das Gerät direkt bei Authentik und löscht den Enrollment-Token vor der Antwort. Der Container läuft absichtlich mit genau einem Worker; mehrere Replikate benötigen zuerst eine gemeinsam genutzte Sperre.

Pro Mitarbeiter bleibt genau ein persönliches Handy aktiv. Zeigt Authentik bereits ein aktives persönliches Gerät, kennzeichnet die Oberfläche den Vorgang als Austausch. Das bisherige Gerät bleibt bis zum erfolgreichen Enrollment des neuen Handys verwendbar und erhält unmittelbar danach den dauerhaften Authentik-Status `disabled`. Die Historie bleibt dadurch in Authentik erhalten. Shared Tablets sind von dieser Austauschregel ausdrücklich ausgenommen.

Die persönliche Device Access Group trägt den lesbaren Namen `Mission Leben Android - Personal - <Benutzername>`. Das Portal findet sie unabhängig vom sichtbaren Namen über das unveränderliche Attribut `mission-leben.de/user-uuid` wieder. Wird ein Benutzername geändert, benennt das Portal dieselbe Gruppe um, statt eine zweite Gruppe oder Freigabe anzulegen.

## Rollen

| Portalrolle | Bestehende Authentik-Gruppe | Bereich |
|---|---|---|
| IT | `BR_IT_MANAGEMENT` | organisationsweit sowie Support |
| Einrichtungsleitung | `BR_EINRICHTUNGSLEITUNG` | eigene `ORG_ML_H*`-Einrichtung |
| PDL | `BR_PFLEGEDIENSTLEITUNG` | eigene `ORG_ML_H*`-Einrichtung |
| Leitung Zentrale | `BR_GESCHAEFTSBEREICHSLEITUNG`, `BR_GESCHAEFTSEINHEITSLEITUNG` oder `BR_ABTEILUNGSLEITUNG` | ausschließlich zusammen mit `ORG_ML_H001` |

Es gibt bewusst keine GF-Rolle und keine parallelen `ML_DEVICE_INIT_*`- oder `ENT_DEVICE_INITIALIZE_*`-Gruppen. Ohne eine der aufgeführten BR-Rollen verweigert der Container die Mitarbeitersuche und Shared-Tablet-Einrichtung. Die persönliche Selbstregistrierung bleibt für aktive Mitarbeiter mit vorhandenem TOTP, Passkey oder registrierter App-Bestätigung erreichbar. Außer IT benötigt jede berechtigte Leitung den passenden `ORG_ML_H*`-Kontext. Sind mehrere Rollen oder Häuser zugewiesen, vereinigt das Portal die erlaubten Bereiche; eine zentrale Leitungsrolle allein erweitert den Zugriff niemals über `ORG_ML_H001` hinaus.

Eine reine Benutzername-/Kennwort-Sitzung reicht nicht aus. Der Bootstrap legt einen anwendungsbezogenen Authentication Flow für Benutzername, Kennwort und ein bereits eingerichtetes TOTP, einen Passkey oder die registrierte App-Bestätigung sowie einen eigenen Authorization Flow mit derselben Faktor-Stufe an. Ein zwei Minuten gültiger, stufen- und gerätegebundener Authentik-Cookie verhindert nur direkt nach der frischen Anmeldung eine doppelte Abfrage. Eine ältere Authentik-Sitzung überspringt die Faktorprüfung nicht. Personen ohne kompatiblen vorhandenen Faktor werden abgewiesen; die Seite bietet keine MFA-Ersteinrichtung an.

## Authentik vorbereiten

Vorher PostgreSQL sichern. Danach `authentik/bootstrap_enrollment_portal.py` in den Authentik-Servercontainer kopieren oder über stdin ausführen. Die einzige Ausgabe ist das API-Token und muss direkt in eine Root-only-Datei geschrieben werden:

```bash
umask 077
podman exec -i authentik-server ak shell \
  < authentik/bootstrap_enrollment_portal.py \
  > enrollment-portal/secrets/authentik-enrollment-api-token
```

Das Skript ist idempotent und erstellt:

- die Anwendung `Gerät einrichten`,
- einen Forward-Auth-Proxy-Provider,
- einen eigenen Authentication Flow für Benutzername, Kennwort und vorhandenes TOTP, Passkey oder registrierte App-Bestätigung,
- einen anwendungsbezogenen Authorization Flow mit verpflichtendem, bereits vorhandenem starken Faktor,
- die Bindung an den eingebetteten Proxy-Outpost,
- ein Servicekonto mit ausschließlich den benötigten API-Rechten.

Die oben genannten BR-Gruppen müssen bereits als kanonische, nicht privilegierte `business_role`-Gruppen existieren. Das Skript legt sie weder an noch verändert es ihre Attribute oder Mitgliedschaften.

Zusätzlich setzt es beim eingebetteten Outpost `authentik_host` und, sofern
noch leer, `authentik_host_browser` auf `https://id.mission-leben.de`. Beim
eingebetteten Outpost muss `authentik_host` die öffentliche Authentik-Adresse
sein; die interne API-Verbindung verwendet weiterhin Authentiks lokalen
IPC-Pfad. Abweichende Umgebungen können dafür
`ML_ENROLL_AUTHENTIK_BROWSER_ORIGIN` setzen. Bedient der eingebettete Outpost
bereits weitere Provider oder ist die Browser-Adresse abweichend belegt,
bricht das Skript aus Sicherheitsgründen ab, statt globale Einstellungen zu
überschreiben.

Der API-Schlüssel ist absichtlich nicht automatisch ablaufend, weil Authentik einen ablaufenden API-Schlüssel serverseitig rotiert, die gemountete Secret-Datei jedoch nicht aktualisieren kann. Er muss betrieblich regelmäßig kontrolliert rotiert werden. Durch seine eng begrenzten Rechte kann er keine Kennwörter ändern, Benutzer anlegen oder Anwendungen administrieren. Für den sicheren Geräteaustausch besitzt er zusätzlich ausschließlich Lese- und Änderungsrechte auf Endpoint Devices; er markiert ersetzte Geräte dauerhaft als deaktiviert und erhält ausdrücklich kein Löschrecht.

`GET /api/v1/devices/status` nimmt ausschließlich einen Authentik-Device-Token im Schema `Bearer+Agent` an. Der Endpunkt validiert den Token über den Authentik-Agent-Connector, liest anschließend Endpoint, Device Access Group und Bindung direkt aus Authentik und verweigert deaktivierte oder abgelaufene Geräte. Bei einem persönlichen Gerät muss genau eine direkte Benutzerbindung existieren und dieser Benutzer weiterhin aktiv sein. Shared-Geräte bleiben an ihre Einrichtungsgruppe gebunden. App und Kommunikations-Bridge verwenden diese Live-Prüfung; es entsteht keine parallele Freigabedatenbank.

Für die App-Bestätigung legt `authentik/bootstrap_app_approval.py` vorher die Authentik-Duo-Stufe an. Deren ausgegebene `duo_stage_uuid` wird als `ML_ENROLL_APP_APPROVAL_STAGE_UUID` gesetzt. Das Portal legt bei jeder persönlichen Geräteinitialisierung idempotent ein bestätigtes Authentik-DuoDevice an: sowohl bei der Selbstregistrierung mit einem vorhandenen starken Faktor als auch bei der Einrichtung durch EL, PDL, zentrale Leitung oder IT. Dessen `duo_user_id` entspricht exakt dem stabilen OIDC-Subject des Benutzers. Shared Tablets erhalten kein solches Gerät. Der fünfminütige Authentik-Abgleich entfernt dieses App-Bestätigungsgerät wieder, sobald der Benutzer kein aktives persönliches Endpoint Device mehr besitzt. Die zusätzlichen globalen Servicekonto-Rechte sind ausschließlich `view_authenticatorduostage` und `add_duodevice`. Authentik 2026.8.3 prüft beim manuellen Import außerdem `add_authenticatorduostage`; das Bootstrap-Skript vergibt dieses Recht nicht global, sondern nur objektbezogen auf die eine bestehende App-Bestätigungsstufe.

Die Rollen- und Organisationsmitgliedschaften werden ausschließlich aus der vorhandenen BR-/ORG-Struktur übernommen. Zentrale Leitungen benötigen zusätzlich `ORG_ML_H001`; EL und PDL benötigen ihre bestehenden `ORG_ML_H*`-Gruppen. IT benötigt keine ORG-Mitgliedschaft. Das Geräteportal pflegt keine dieser Mitgliedschaften selbst.

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
