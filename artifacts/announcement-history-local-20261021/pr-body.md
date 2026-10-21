## Ziel

Ankündigungen bleiben 14 Tage ab Veröffentlichung in der Glocke lesbar, auch
nach lokalem Ausblenden, Ablauf oder ausdrücklichem Löschen in Nextcloud.
Quellenlöschung ist für diese Historie nach ausdrücklicher Nutzerklärung kein
Widerruf; aktuelle Benutzer-/Gruppenberechtigungen bleiben zwingend.

## Umsetzung

App 0.23.3/97, Bridge 0.10.13 (weiter Schema 12), Adapter 0.3.4. Eigene
ICrypto-verschlüsselte Tabelle mit begrenzter Aufbewahrung; Entdeckungsdurchlauf
archiviert veröffentlichte Inhalte, ohne Nextcloud Core oder Announcement Center
zu patchen. Neue signierte Historienroute, keine veraltete Cache-Antwort als
Rückfall und kein neuer Inhaltscache am Handy. Historie verändert weder
Startseiten-/Push-Filter noch MFA, Gerätesperre oder Authentik-Gruppen.

## Prüfung und Status

Lokal umgesetzt und produktiv signiertes Test-APK gebaut; **NICHT ausgerollt**,
kein OTA, noch nicht auf einem realen Handy installiert. 415 Debug-/383
Release-Tests, 0 Fehler; Lint 0 Fehler/130 Warnungen. 176 Bridge-Tests im finalen
Python-3.13-Container und 43 isolierte PHP-Prüfungen bestanden. PHP-Domain- und
Migrationsstubs sind kein Beleg für eine echte Datenbankmigration oder eine
signierte Live-Route. Echte Quelle-Löschung/Ablauf sowie Handyabnahme stehen aus.

Details und Rückweg: `artifacts/announcement-history-local-20261021/README.md`
und `evidence.json`. Bereits vor Aktivierung gelöschte Quellen sind nicht
rückwirkend wiederherstellbar. Bei versehentlich veröffentlichten sensiblen
Inhalten ist nach Quellenlöschung zusätzlich gezieltes Bereinigen des eigenen
Archivs nötig. Fremde lokale Bootstrap-Arbeiten sind unverändert ausgeschlossen.
