## Problem und Ziel

Neue Ankündigungen waren bisher nur beim Aktualisieren der App-Ansicht sichtbar, nicht als eigener Android-Push. Auf der Startseite konnten Hinweise die Terminanzeige verdrängen und ließen sich nicht direkt als gelesen ausblenden.

## Umsetzung und Sicherheitsgrenzen

Eng typisierte interne Aktion open_announcements, eigener Android-Kanal und native Glocke statt frei vorgegebener URL. Signierte lesende Nextcloud-Schnittstellen prüfen aktive Identität und aktuelle Zielgruppen serverseitig. Keine historische Massenversendung und keine Zustellung an inkompatible alte App-Versionen. Kein Ankündigungstext im ntfy-Transport. Keine Änderungen an Authentik-Gruppen, Flows, MFA oder lokaler Gerätesperre; keine neuen Android-Berechtigungen.

## Abnahme

Android-/Bridge-/PHP-Tests, signierte Live-Schnittstellen und Einzelhandy-Pilot abgeschlossen. Neue Veröffentlichung automatisch erkannt, eine Zustellung, eigene Android-Meldung, Tippen/Glocke, Auf-/Zuklappen und Gelesen/Erhalt in Glocke am realen Gerät geprüft. Gleichzeitige Agenda synthetisch geprüft; mehrere Handys, restriktive Zielgruppe und Netzunterbrechung nicht zusätzlich live durchgespielt. OTA-Veröffentlichung nach ausdrücklicher Nutzerfreigabe.
