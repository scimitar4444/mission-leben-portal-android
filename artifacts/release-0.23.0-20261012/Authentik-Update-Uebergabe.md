# Informationsübergabe: Android 0.23.0

Nutzerfreigegebenes OTA-Release vom 12.10.2026. Keine Beauftragung für weitere
produktive Änderungen und keine Gruppen-/Flow-Entscheidung.

## Stand und Quellen

- Android-Code: `341b35ebeefd98b0b448535c9b91b104e9df2f10`, Tag `v0.23.0`,
  versionCode 94. Produktionssignatur unverändert. GitHub bleibt OTA-Quelle;
  F-Droid und stille Installation sind nicht enthalten.
- Seit 0.22.0: eigene IT-Hilfe-Push-Kategorie, lokale Glockenübersicht,
  App-Favoriten, kontextuelles Problemformular, bebilderte App-Hilfe,
  native Foto-Anhänge und klar getrennte Android-Benachrichtigungs-/Akku-Einträge.
- Die neue Foto-Funktion erweitert keine Android-Berechtigung. Ein separater,
  nicht exportierter `TicketPhotoProvider` verhindert die im Pilottest erkannte
  Kollision mit dem APK-Update-Provider. Temporäre URI-Freigaben und vorhandene
  Geräte-/Sitzungssperren bleiben wirksam. WebView-Kamera/Mikrofon bleibt gesperrt.
- `6857d9823bfda52b2a0ddb922ef0c1c36387c5d1`: IT-Hilfe-Ereignisschnittstelle.
  Frühere Live-Ausbringung ist in `artifacts/helpdesk-push-20261012/evidence.json`
  dokumentiert: Bridge 0.10.11, SQLite-Schema 11, additive OTOBO-Erweiterung;
  keine Überschreibung von OTOBO-Kerndateien und keine Gruppen-/Flow-Änderung.
  In diesem Release-Turn wurde davon nichts erneut ausgebracht oder neu gestartet.
- `authentik/export_communication_assignments.py` unterstützt die freigegebene
  IT-Hilfe-Anwendung in den Kommunikationszuordnungen. SHA-256:
  `783917aa15c7836c84a885bd3ddc629cf61607e58acd2370cb5db4231a740484`.
- `8ab9137eba56b30d40f16d138e65c7f5e19cd7b3`: vorhandener CLI-Metrics-Guard
  im Quellstand. `authentik/deploy/cli-metrics-guard/Dockerfile` SHA-256
  `75dd977e4702c7e95c21f680c679cbbe81aaec0b22c0e97ab5038b330fc75de6`;
  `ak-cli-metrics-guard.sh` SHA-256
  `996e84ddc4d23cb6957d4e55c372a799bb5d95dcf609bf0a11e23fd4c5f07e14`.
  Hier keine Image-/Mount-Ausbringung durchgeführt.

## Nach künftigen Authentik-Updates prüfen

1. Genehmigten Authentik-Image-/Wrapper-/Mount-Stand einschließlich Metrics-Guard
   prüfen; nicht ungeprüft durch ein Standard-Image oder einen älteren Snapshot ersetzen.
2. Den Kommunikations-Exporter samt Hash und Timer beibehalten. Eine aktuell
   freigegebene IT-Hilfe-Anwendung muss automatisch eine passende Zuordnung
   erhalten; ohne Freigabe darf kein Empfänger erzeugt werden.
3. Bridge-Gesundheit, bestehende ntfy-Zuordnungen, signierte OTOBO-Quellenroute und
   Schema 11 prüfen. Keine neuen Gruppen/Rechte aus diesem Release ableiten.
4. Bestehende Abnahmepfade für persönliche Geräte, Gruppenkonto-Diensthandys,
   Tablets, Duo-Anfragen, Erneuerung, Sperre und Abmeldung beibehalten. Die
   90-Tage-Regel und Flow-/Provider-Verträge wurden durch dieses Release nicht geändert.
5. Mit einem berechtigten Pilotkonto das Laden der Anwendungen und Kommunikations-
   Zuordnungen prüfen. Ein echter sichtbarer IT-Ticketantwort-Push samt passendem
   Ticketziel ist weiterhin als eigener E2E-Abnahmepunkt zu führen.

## Belege und Grenzen

- Sauberer signierter Build aus committed Git-Archiv: 359 Release-Tests,
  386 Debug-/Compose-Tests, jeweils null Fehler. Lint: null Fehler, 130 Warnungen.
- Sieben JavaScript-/DOM-Prüfprogramme erfolgreich; Bootstrap-Vertrag 17 Tests,
  Kommunikationszuordnung zwei Tests, OTOBO-Konfiguration zwei Tests erfolgreich.
- GitHub CI `38311486936` am Release-Code: alle vier Jobs erfolgreich
  (Android, Bootstrap-Quelle, Bridge, Enrollment-Portal).
- Guides auf Nextcloud 34.0.4.1 veröffentlicht; drei Seiten geändert, drei Seiten
  und alle 30 Bildanhänge unverändert. Text-Sitzungen ausschließlich gezielt und
  ohne `force` erneuert; Browsertexte, Links und fünf App-Hilfebilder geprüft.
- Foto-Rückkehr auf TCL T807D durch den Nutzer bestätigt. Kein Ticket durch den
  Agenten abgeschickt. Samsung-Kamera und echter IT-Antwort-Push noch nicht E2E abgenommen.
- Der vollständige OTA-Download-/Signaturbeleg steht in `release-evidence.json`.
  Die finale OTA-Installation auf dem Handy bleibt Nutzerabnahme; lokale Testversion
  gleicher Funktionen war installiert und vom Nutzer geprüft.
- Fremde uncommittete Bootstrap-Änderungen und Instrumentierung waren nicht im
  Git-Archiv und wurden nicht veröffentlicht. Keine Secrets übertragen.

## Rückweg

Das vorherige Release `v0.22.0` bleibt erhalten. Bei Bedarf das neue Angebot
stoppen und eine produktionssignierte Vorwärtskorrektur mit versionCode > 94
erstellen; keine stille Android-Rückstufung behaupten. App-Registrierung nicht
löschen. Für die frühere OTOBO-Integration kann nur
`MissionLebenPush::Enabled=0` gesetzt werden; vorhandenes Bridge-Schema 11 nicht
mit einer veralteten SQLite-Sicherung überschreiben. Der Guide-Rückweg ist
separat, Hash- und Text-Edit-geschützt in `update_app_guides.php` dokumentiert.
