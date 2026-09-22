# Kontakte in der Android-App

Ab App 0.12.0 / Bridge 0.10.0: unternehmensweites Mitarbeiterverzeichnis,
Suche nach Name, Einrichtung, Funktion oder Abteilung; optional „Meine Einrichtung“.
Telefon und Mobil öffnen die Wählansicht (kein automatischer Anruf). Ab App 0.12.1
öffnet E-Mail eine neue Nachricht mit Empfänger in Zimbra im geschützten App-Browser
(kein automatischer Versand, keine lokale Mail-App). Die vorhandene Zimbra-Kachel
muss verfügbar sein; Geräte-/Sitzungsprüfung und Zimbra-SSO bleiben unverändert.
Der Posteingang wird als Zurück-Ziel vorbereitet; „Portal“ verlässt den Browser.
Talk-Direktchat ist nicht
Teil dieser Version. Keine Android-Kontaktberechtigung und kein Adressbuchexport.

## Daten und fachlicher Vertrag

Authentik bleibt führend für die ausgewertete Identität und ORG-Zuordnung.
Keine neue Gruppe, keine geänderte Mitgliedschaft und keine Änderung an Flows.
Der read-only Export verwendet dieselbe `real_facility`-Definition wie das
abgestimmte Geräteportal: verwaltete `ORG_ML_Hxxx[_xx]`, organization_unit,
AKTIV/UMGESETZT_UEBERGANG, Einrichtung oder Einrichtung/Verbund; H001 zusätzlich
Geschäftseinheit/Standort. Abteilungen/Teilbereiche sind keine Standorte.
`all_groups()` vermittelt Eltern-Einrichtungen aus Untergruppen. Persönliche
Konten können mehrere Einrichtungen im Filter haben; gemeinsame Tablets
verwenden ausschließlich ihr einzelnes gebundenes Haus für „Meine Einrichtung“.

Aufgeführt werden nur aktive `person`/`person`-Konten (keine service_account).
Shared-Konten dürfen das Verzeichnis über ihr zulässiges Tablet nutzen, werden
aber selbst nicht als Mitarbeitende aufgeführt. Keine Berechtigung aus
„Mobil erreichbar“, Einrichterrollen oder den alten ML_DEVICE_INIT-Gruppen.

Explizite Feldliste:

| Kontaktfeld | Quelle |
| --- | --- |
| Name | User.name |
| E-Mail | User.email, ausschließlich mission-leben.de / akademie-mission-leben.de |
| Telefon | telephoneNumber |
| Mobil | mobile |
| Funktion | employee_job_title, sonst title |
| Abteilung | employee_department, sonst department |
| Einrichtung | iam_display_name der effektiven echten ORG-Einrichtungen |

Der Betreiber hat am 22.09.2026 ausdrücklich bestätigt: **telephoneNumber und
mobile sind Firmennummern**. Keine Ausweichwerte aus homePhone, phone_number,
Freitext oder Privatadressen. Die Zuordnung dieser Quellfelder muss bei einer
Änderung der führenden Datenquelle erhalten/erneut geprüft werden. Ungültige
Telefon-/Mailwerte werden weggelassen; gleich formatierte Telefonnummern werden
nach Entfernen von Trennzeichen dedupliziert. Keine Notes, Geburtstage,
Personalnummern, Vollgruppenliste oder sonstigen Attribute im App-Ergebnis.
In Authentik können auch private oder externe E-Mail-Adressen stehen; diese
werden nicht veröffentlicht. Weitere Firmendomänen müssen ausdrücklich geprüft
und in BUSINESS_EMAIL_DOMAINS ergänzt werden (kein Suffix-/Teilstringvergleich).

## Schnittstelle und Schutz

`POST /device-bridge/v1/contacts/search`

- Bearer: aktueller OIDC-Access-Token; Userinfo wird bei jeder Anfrage geprüft.
- `X-ML-Device-Token`: Gerätecredential; zentraler Gerätestatus wird live geprüft.
- JSON: `q` (max. 100 Zeichen), `mine` (Boolean), `offset` (Integer ab 0).
- Höchstens 40 Treffer/Antwort, `total`, `next_offset`, `my_facilities`, `updated_at`.
- Persönlich: exakt das gebundene aktive persönliche Konto. Shared: aktives
  person/person- oder shared-Konto in der exakt gebundenen Einrichtung.
- Diese Bindung wird auch in der Bridge geprüft, unabhängig von Push-Freigaben.
- HTTP 401 ohne gültige Sitzung, 403 bei fehlender/fremder Bindung, 503 bei
  nicht verfügbarem oder veraltetem Verzeichnis. Kein Fail-open-Fallback.
- Suchbegriffe stehen im POST-Body, nicht in URLs/Zugriffslogs. Antworten no-store.
- App hält nur die angezeigten Ergebnisse im RAM, keine gespeicherten Kontakte.
  Beim Verlassen der Ansicht/Abmelden und beim Wechsel in den Hintergrund werden
  Ergebnisse verworfen. Rückkehr lädt neu. Deutsch als Rückfall, alle vorhandenen
  neun App-Sprachen sind abgedeckt.

## Betrieb

Der read-only Export nutzt den vorhandenen Authentik-Container, ohne Token oder
zusätzliche Benutzer-API-Berechtigungen an die App zu geben. Ein Host-Timer
aktualisiert alle fünf Minuten atomar
`/opt/mission-leben-bridge/directory-data/employee-directory.json` (root:10001,
0640; Volume in Bridge read-only). Er ist unabhängig von Talk-/Zimbra-Abgleichen.
Es werden keine alten Verzeichnisstände archiviert. Kein weiterer Container.

Änderungen/Entfernungen und neue Gerätebindungen werden spätestens beim nächsten
erfolgreichen Lauf übernommen (rund fünf Minuten plus Laufzeit). Eine Geräte-
sperre wird über den zentralen Status zusätzlich live geprüft. Nach sieben
Minuten ohne frischen Export ist die Suche gesperrt, auch wenn eine alte Datei
noch existiert. Es gibt keine Offline-Freigabe veralteter Verzeichnisdaten.

Installation: `sudo authentik/deploy/install-employee-directory.sh`.
Die Bridge benötigt `BRIDGE_EMPLOYEE_DIRECTORY_FILE` mit dem oben genannten
Containerpfad `/run/mission-leben-directory/employee-directory.json`; dies ist im
Pilot-Compose gesetzt. Für andere Compose-Dateien die vorhandene directory-data-
Einbindung und diese Umgebungsvariable übernehmen.

Nach Authentik-Upgrades: Export manuell starten, Service-Exitstatus und Schema
prüfen; alte Daten werden bei Fehlern nicht erneuert. Keine Core-Patches.
Rollback: altes Bridge-Image/Compose zurücksetzen, neuen Timer deaktivieren.
Die ältere App ist mit der erweiterten Bridge unverändert kompatibel.

Tests: `PYTHONPATH=bridge/src python3 -m unittest discover -s bridge/tests` sowie
Android-Unit-Tests und Lint. Die physischen Tests (Wählansicht/Zimbra-Verfassen, Schrift-
größen und eigene Einrichtung auf Handy/Tablet) müssen mit der installierten
App durchgeführt werden; ein erfolgreicher Build ersetzt diese Prüfung nicht.
