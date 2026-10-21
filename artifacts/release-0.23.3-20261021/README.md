# OTA 0.23.3/98 – veröffentlicht und öffentlich geprüft

Am 21.10.2026 um 20:22:56 UTC als reguläres Latest veröffentlicht:
https://github.com/scimitar4444/mission-leben-portal-android/releases/tag/v0.23.3

Der Nutzer hatte die Veröffentlichung zunächst angehalten und nach der kurzen
allgemeinen 14-Tage-Erklärung erneut freigegeben („Okay. Dann ota“).
Archivtext: „Hier findest du neue und gelesene Meldungen der letzten 14 Tage.“

versionCode 98 statt Pilot 97, sichtbare Version 0.23.3 unverändert: Auch bereits
installierte Pilotgeräte bekommen das öffentliche OTA angeboten. Die finale APK
wurde aus dem sauberen Commit 4c7d649 gebaut und produktiv signiert. Der
Merge 584f0bc hat denselben vollständigen Quellbaum. Alle fünf PR-Prüfungen sowie
415 Debug-/383 Release-Tests bestanden; Lint 0 Fehler/130 Warnungen. Öffentliche
APK/Latest-Manifest vollständig und ohne Authentifizierung per HTTPS geladen,
bytegleich mit lokalen Dateien, Signatur und Prüfsummen geprüft.

Funktionsabnahme auf echtem Handy stammt vom Pilot 97: sichtbarer Push,
Gelesen/Home/Glocke, Ablauf und manuelles Löschen in Nextcloud. Die abschließende
Textkorrektur in neun Sprachen und interne Build-/Release-Notes-Nummer 98 wurden
automatisiert geprüft. Keine Behauptung, dass die finale 98 schon auf dem
abgezogenen Handy oder auf anderen Geräten installiert ist. Wachhaltung nach
Pilot nachweislich wieder auf 0 gesetzt.

Hashes, CI, Backend-Migration/Rückweg und offene Grenzen:
`release-evidence.json` sowie `../announcement-history-local-20261021/evidence.json`.
