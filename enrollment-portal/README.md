# Mission Leben Geräte-Einrichtung

Der Container stellt die bewusst einfache Oberfläche **Gerät einrichten** bereit. Er speichert weder Benutzer noch Geräte. Authentik 2026.8.3 bleibt die alleinige Quelle für Benutzer, `ORG_*`-Gruppen, Endpoint Devices, Device Access Groups, Bindungen, Enrollment-Tokens und Audit-Ereignisse.

## Bedienung

Für die reine Selbstinstallation steht die öffentliche, nicht ablaufende PC-Seite `/download` bereit. Ihr QR-Code führt direkt zur aktuellen GitHub-Release-APK. Nach „App installiert“ zeigt die Seite statt eines zweiten QR-Codes die Anmeldung mit persönlichem Benutzernamen, Passwort und vorhandenem TOTP in der App. Der Link enthält keinen Enrollment-Token. Die IT sucht unter `/download/send` eine aktive physische Person und kann ihr diesen Link direkt per E-Mail senden; dabei wird noch kein Gerät oder Registrierungstoken angelegt.

Für Mitarbeiter mit vorhandenem TOTP, Passkey oder bereits registrierter App-Bestätigung öffnet die App direkt `/self`. Nach Benutzername, Passwort und dem gewählten Faktor bestätigt die Person nur noch „Gerät jetzt registrieren“. Der Container bindet das Gerät an genau dieses angemeldete Konto und leitet automatisch zur App zurück. Beim allerersten persönlichen Gerät steht noch keine App-Bestätigung zur Verfügung; dafür bleibt TOTP oder Passkey erforderlich.

Für eine Einrichtung im Auftrag:

1. Als IT, EL, stellvertretende EL oder PDL die Verwaltungsseite öffnen.
2. Gerätetyp wählen: Mitarbeiter-Handy oder gemeinsames Tablet. Nur der IT wird außerdem „Gruppenkonto auf Diensthandy“ angeboten.
3. Mitarbeiter beziehungsweise Einrichtung auswählen.
4. Auf der PC-Seite zuerst die Android-/Samsung-Vorbereitung erledigen. Den ersten QR-Code mit der normalen Handykamera scannen: Er lädt direkt die GitHub-Release-APK herunter. Installation auf dem Handy bestätigen.
5. Auf derselben PC-Seite **App installiert** anklicken. Erst dann wird der zweite, einmalige QR-Code zur Registrierung angezeigt. Diesen ebenfalls mit der Handykamera scannen; der verifizierte HTTPS-App-Link öffnet die installierte App direkt.

Die IT kann den Einrichtungslink kopieren und ausschließlich an die vorgesehene Person mailen. Auch dieser Link wird **am PC** geöffnet und zeigt dieselben Schritte; er ist kein zusätzlicher mobiler Bestätigungsschritt. Seine Gültigkeit von 30 Minuten beginnt bereits bei der Erstellung durch den Einrichter, nicht erst beim Klick auf „App installiert“. Danach oder nach erfolgreicher Registrierung ist ein neuer Link nötig. Die Samsung-Vorbereitung berücksichtigt sowohl „Apps aus dieser Quelle zulassen“ als auch die zusätzliche „Automatische Sperre“. Da die Sperre direkte APK-Updates ebenfalls blockiert, muss sie für den eigenen OTA-Updateweg ausgeschaltet bleiben; eine von One UI angebotene automatische Reaktivierung nach 30 Minuten ist abzulehnen. Der allgemeine Download-QR-Code auf der Startseite bleibt als separater Selbsthilfe-Einstieg bestehen. Das Enrollment-Geheimnis steht im PC-Link und im Android-App-Link ausschließlich im URL-Fragment hinter `#`; Browser und Reverse Proxy senden es daher nicht in der URL an den Webserver oder in dessen Zugriffsprotokoll. Beim Klick auf „App installiert“ wird der Token im POST-Body zur Gültigkeitsprüfung übertragen; nur der Besitzer des Links kann dadurch den zweiten QR-Code abrufen. Dieser Klick verlängert den Token nicht.

Die Android-App übernimmt den Gerätemodus aus dem QR-Code. Der öffentliche Redeem-Endpunkt registriert das Gerät direkt bei Authentik und löscht den Enrollment-Token vor der Antwort. Der Container läuft absichtlich mit genau einem Worker; mehrere Replikate benötigen zuerst eine gemeinsam genutzte Sperre.

Pro Mitarbeiter bleibt genau ein persönliches Handy aktiv. Zeigt Authentik bereits ein aktives persönliches Gerät, kennzeichnet die Oberfläche den Vorgang als Austausch. Das bisherige Gerät bleibt bis zum erfolgreichen Enrollment des neuen Handys verwendbar und erhält unmittelbar danach den dauerhaften Authentik-Status `disabled`. Die Historie bleibt dadurch in Authentik erhalten. Derselbe Austauschweg gilt für ein direkt an ein Gruppenkonto gebundenes Diensthandy; ein paralleler Tablet-Zugang zu diesem Konto wird dagegen nur organisatorisch geregelt. Shared Tablets sind von der Handy-Austauschregel ausdrücklich ausgenommen.

Die persönliche Device Access Group trägt den lesbaren Namen `Mission Leben Android - Personal - <Benutzername>`. Das Portal findet sie unabhängig vom sichtbaren Namen über das unveränderliche Attribut `mission-leben.de/user-uuid` wieder. Wird ein Benutzername geändert, benennt das Portal dieselbe Gruppe um, statt eine zweite Gruppe oder Freigabe anzulegen.

## Automatischer IT-Mailversand

Nur `BR_IT_MANAGEMENT` darf E-Mails aus dem Geräteportal versenden. Für ein persönliches Handy wird die in Authentik hinterlegte Adresse des ausgewählten Mitarbeiters verwendet. Beim Gruppenkonto-Diensthandy wählt die IT zuerst das Gruppenkonto und sucht dann im E-Mail-Empfängerfeld eine aktive physische Person mit `@mission-leben.de`-Adresse. Der Einrichtungslink geht ausschließlich per E-Mail an diese Person, niemals automatisch an die gemeinsame Mailbox; eine Bildschirm-/Kopieroption gibt es für diesen Weg nicht. Die Empfängerperson wird dadurch nicht an das Gerät gebunden. Der reine TOTP-Installationslink `/download` kann ebenfalls nach Personensuche gemailt werden und enthält keinen Registrierungscode. Für den Versand müssen `ML_ENROLL_SMTP_HOST`, `ML_ENROLL_SMTP_PORT` und `ML_ENROLL_SMTP_SENDER` gesetzt sein. Der SMTP-Relay muss STARTTLS mit gültigem Zertifikat anbieten. Scheitert eine zeitlich begrenzte E-Mail für ein persönliches Mitarbeiter-Handy nach Token-Erstellung, bleibt dessen Einrichtungslink zur manuellen Weitergabe sichtbar; beim Gruppenkonto-Diensthandy wird bei Versandfehler dagegen kein Link im Browser angezeigt. Absender ist `it-service@mission-leben.de`.

## Rollen

| Portalrolle | Bestehende Authentik-Gruppe | Bereich |
|---|---|---|
| IT | `BR_IT_MANAGEMENT` | organisationsweit sowie Support |
| Einrichtungsleitung | `BR_EINRICHTUNGSLEITUNG` | alle eigenen echten Einrichtungen |
| Stellvertretende Einrichtungsleitung | `BR_STELLVERTRETENDE_EINRICHTUNGSLEITUNG` | alle eigenen echten Einrichtungen |
| PDL | `BR_PFLEGEDIENSTLEITUNG` | alle eigenen echten Einrichtungen |

Geschäftsbereichs-, Geschäftseinheits-, Abteilungs- und Geschäftsführungsleitungen sind keine Einrichter. Es gibt keine parallelen `ML_DEVICE_INIT_*`- oder `ENT_DEVICE_INITIALIZE_*`-Berechtigungen. Ohne eine der vier aufgeführten BR-Rollen verweigert der Container die Mitarbeitersuche und Shared-Tablet-Einrichtung. Die persönliche Selbstregistrierung bleibt für aktive Mitarbeiter mit vorhandenem TOTP, Passkey oder registrierter App-Bestätigung erreichbar. Außer IT benötigen EL, stellvertretende EL und PDL mindestens eine eigene echte Einrichtung. Gehören sie zu zwei oder drei Einrichtungen, zeigt das Portal alle dedupliziert an; für ein Tablet wird genau eine davon gewählt.

Eine auswählbare Einrichtung entspricht dem verbindlichen Gruppeninterface: Der Name folgt `^ORG_ML_H[0-9]{3}(?:_[0-9]{2})?$`, `iam_group_type=organization_unit`, `iam_managed=true`, `iam_plan_status` ist `UMGESETZT_UEBERGANG` oder `AKTIV` und `iam_org_level` ist `Einrichtung` oder `Einrichtung/Verbund`. Ausschließlich `ORG_ML_H001` darf stattdessen `iam_org_level=Geschäftseinheit/Standort` tragen und steht für den Standort Zentrale. Untergruppen, Teilbereiche und `ORG_ML_ZD_*` sind keine Gerätestandorte. IT sieht alle so ermittelten Einrichtungen; EL, stellvertretende EL und PDL nur die Schnittmenge mit ihren effektiven Authentik-Gruppen.

Der gewöhnliche persönliche App-Modus ist für aktive physische Mitarbeitende freigegeben: `iam_account_kind=person` und `iam_directory_class=person`. Als eng begrenzte Ausnahme darf die IT ein aktives, interaktiv zugelassenes `shared/mailbox`-Konto auf ein Diensthandy binden; dieser IT-exklusive Weg setzt die Geräte-Eigentumsmarkierung ohne zusätzliche Checkbox. Die Voraussetzungen und Betriebsgrenzen stehen in [SHARED_ACCOUNT_COMPANY_PHONE.md](../docs/SHARED_ACCOUNT_COMPANY_PHONE.md). Bei einem gemeinsamen Tablet wählt der berechtigte Einrichter genau eine seiner erlaubten Einrichtungen. Das Portal bindet das Tablet über die `DeviceAccessGroup` `Mission Leben Android - Shared - <ORG>` und `mission-leben.de/facility-group=<ORG>` genau an diese eine `ORG_ML_H*`. Authentiks vorhandene `check_device_policies`-Prüfung erzwingt anschließend diese Gruppenbindung; innerhalb der gebundenen Einrichtung sind aktive physische Mitarbeitende sowie Konten mit `iam_account_kind=shared` zugelassen. Es gibt keine zusätzliche Tablet-, `ENT_*`- oder `APP_*`-Berechtigungsgruppe. `Mobil erreichbar` und `ml_features` verändern diese Geräteberechtigungen nicht.

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

`GET /api/v1/devices/status` nimmt ausschließlich einen Authentik-Device-Token im Schema `Bearer+Agent` an. Der Endpunkt validiert den Token über den Authentik-Agent-Connector, liest anschließend Endpoint, Device Access Group und Bindung direkt aus Authentik und verweigert deaktivierte oder abgelaufene Geräte. Bei einem persönlichen Gerät muss genau eine direkte Benutzerbindung existieren und dieser Benutzer weiterhin aktiv sein. Beim markierten Gruppenkonto-Diensthandy werden zusätzlich Kontoart, Firmeneigentum und genau ein aktives Handy geprüft. Shared-Tablets bleiben an ihre Einrichtungsgruppe gebunden. App und Kommunikations-Bridge verwenden diese Live-Prüfung; es entsteht keine parallele Freigabedatenbank.

Für die App-Bestätigung legt `authentik/bootstrap_app_approval.py` vorher die Authentik-Duo-Stufe an. Deren ausgegebene `duo_stage_uuid` wird als `ML_ENROLL_APP_APPROVAL_STAGE_UUID` gesetzt. Das Portal legt bei jeder persönlichen **Mitarbeiter**-Geräteinitialisierung idempotent ein bestätigtes Authentik-DuoDevice an: sowohl bei der Selbstregistrierung mit einem vorhandenen starken Faktor als auch bei der Einrichtung durch EL, stellvertretende EL, PDL oder IT. Dessen `duo_user_id` entspricht exakt dem stabilen OIDC-Subject des Benutzers. Shared Tablets und Gruppenkonto-Diensthandys erhalten kein solches Gerät. Der fünfminütige Authentik-Abgleich entfernt dieses App-Bestätigungsgerät wieder, sobald der Benutzer kein aktives persönliches Endpoint Device mehr besitzt. Die zusätzlichen globalen Servicekonto-Rechte sind ausschließlich `view_authenticatorduostage` und `add_duodevice`. Authentik 2026.8.3 prüft beim manuellen Import außerdem `add_authenticatorduostage`; das Bootstrap-Skript vergibt dieses Recht nicht global, sondern nur objektbezogen auf die eine bestehende App-Bestätigungsstufe.

Die Rollen- und Organisationsmitgliedschaften werden ausschließlich aus den effektiven Authentik-Gruppen übernommen. EL, stellvertretende EL und PDL benötigen ihre bestehenden echten Einrichtungsgruppen; IT benötigt keine ORG-Mitgliedschaft. Das Geräteportal pflegt keine dieser Mitgliedschaften selbst. Eine Mitgliedschaft in einer ORG-Untergruppe kann über Authentiks rekursive `all_groups()`-Auflösung den Zugriff auf die übergeordnete Einrichtung vermitteln, macht die Untergruppe aber nicht zu einem auswählbaren Gerätestandort. Das Gruppenkonto-Diensthandy bleibt ausschließlich IT vorbehalten.

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

In `.env` muss `ML_ENROLL_AGENT_CONNECTOR_UUID` auf den vorhandenen Connector `Mission Leben Android`, `ML_ENROLL_APP_APPROVAL_STAGE_UUID` auf die ausgegebene App-Bestätigungsstufe und `ML_ENROLL_ANDROID_CERT_SHA256_FINGERPRINTS` auf den SHA-256-Fingerabdruck des endgültigen Android-Produktionszertifikats zeigen. Der Container bindet nur an `127.0.0.1:8081`; extern wird ausschließlich der Reverse Proxy veröffentlicht. `deploy/nginx-forward-auth.conf` ist ein gehärtetes Beispiel für `geraete.mission-leben.de`. `/setup`, `/download`, `/static/setup.js`, `/api/v1/setup/qr`, `/install`, die für diese Seiten nötigen statischen Dateien und `/.well-known/assetlinks.json` müssen ohne Authentik-Anmeldung erreichbar sein; alle Verwaltungsseiten und Mail-POSTs bleiben geschützt. Der öffentliche QR-Vorschau-Endpunkt ist nur per POST mit einem gültigen, nicht abgelaufenen Token verwendbar und am Proxy rate-limitiert.

## Tests

```bash
python -m venv .venv
.venv/bin/pip install -e '.[test]'
.venv/bin/pytest -q
podman build -t mission-leben-device-enrollment .
```
