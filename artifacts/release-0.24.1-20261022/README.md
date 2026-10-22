# OTA 0.24.1 / 102 – veröffentlicht und öffentlich geprüft

Am 22.10.2026 um 14:05:06 Uhr (Europe/Berlin) als reguläres Latest veröffentlicht:
https://github.com/scimitar4444/mission-leben-portal-android/releases/tag/v0.24.1

Enthalten ist die Anmeldenamen-Hilfe für die Android-Authentik-Anmeldung:
verständliche Erklärung statt versehentlicher E-Mail-Endung oder führendem @,
bewusste Übernahme passender Schreibvorschläge und verständlichere allgemeine
Ablehnung mit einblendbarer technischer Originalmeldung. Keine automatische
Anmeldung, kein Raten existierender Konten, keine Passwort-/TOTP-Zugriffe.
Serverseitige Berechtigungen, Endpoint Trust, MFA, Gruppen und Flows unverändert.

Nach realer Biometrie auf dem Nutzerhandy wurden die Eingabe- und Korrekturpfade
im tatsächlichen Authentik-Shadow-DOM der Android-WebView geprüft. Ein zusätzlicher
No-Submit-Wächter verhinderte künstliche Anmeldeversuche. Registrierung und bestehende
App-Sitzung erhalten. Keine neue echte Kennwort-/TOTP-Anmeldung und kein Live-Test
eines gesperrten Geräts behauptet. Temporäres Prüfprogramm entfernt, Wachhaltung0.

Finaler sauberer Commit-Archiv-Build: 439 Debug- und 407 Release-Tests bestanden,
Lint0 Fehler/131 bekannte Warnungen, Node-Verträge und alle fünf PR-Prüfungen grün.
PR19 gemergt; Merge und gebauter Commit haben denselben Baum. Öffentliches
Latest-Manifest und vollständige APK anonym per HTTPS geladen: bytegleich,
Hash, Größe und unverändertes produktives Signierzertifikat geprüft.
Keine neuen Android-Berechtigungen.

Code102 aktualisiert öffentliche100 und lokalen Pilot101 ohne Datenlöschung.
Der Pilot erhält die finale Version auf Nutzerwunsch per OTA; eine weitere direkte
Installation oder eine bereits aktualisierte Flotte wird nicht behauptet.
Die fremde lokale Bootstrap-Änderung ist weiterhin unverändert und ausgeschlossen.

Hashes, Abnahmegrenzen, Rückweg und Updatevertrag: `release-evidence.json`.
