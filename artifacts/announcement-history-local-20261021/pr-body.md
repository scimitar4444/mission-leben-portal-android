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

Serverteile ausgerollt und Einzelgeräteabnahme auf App0.23.3/97 erfolgreich.
415 Debug-/383 Release-Tests, 0 Fehler; Lint 0 Fehler/130 Warnungen. 176
Bridge-Tests im finalen Python-3.13-Container und43 isolierte PHP-Prüfungen
bestanden. Eigene Migration auf NC35.0.2/MariaDB real ausgeführt, ursprüngliche
Quelltabellenschemata bytegleich, signierte Historienroute200; unsigniert401,
fehlende UID auch mit gültiger Mail404. Bridge healthy/Schema12/FK0.

Echter Nutzerhinweis20 nach Ablauf physisch aus Nextcloud entfernt und trotzdem
unter der Glocke lesbar; Hinweis21 automatisch per Push einmal auf dem Handy
sichtbar, „Gelesen“ entfernt Home-Karte/Badge/Tray und behält die Glocke.
Danach21 durch Nutzer bewusst vor Ablauf gelöscht: Quell-/Gruppenzeilen0,
frischer Historienabruf zeigt weiter Archiviert und lesbaren Inhalt. Wachhaltung
auf ursprünglichen Wert0 zurückgestellt. Kein Mehrgeräte-/eingeschränkter
Gruppenpilot und kein14-Tage-Wandzeitnachweis; diese Grenzen sind automatisiert
getestet. Nach kurzem Nutzerstopp wurde der Archivtext in allen9Sprachen auf
eine kurze allgemeine14-Tage-Erklärung für sämtliche Meldungen gekürzt.
Finales Release0.23.3/98 (statt Pilot97), damit auch Pilotgeräte OTA erhalten.
Die Funktionsabnahme bleibt Pilot97; abschließende Textänderung wird synthetisch
geprüft. OTA nach dieser Korrektur erneut ausdrücklich beauftragt, noch nicht publiziert.

Details und Rückweg: `artifacts/announcement-history-local-20261021/README.md`
und `evidence.json`. Bereits vor Aktivierung gelöschte Quellen sind nicht
rückwirkend wiederherstellbar. Bei versehentlich veröffentlichten sensiblen
Inhalten ist nach Quellenlöschung zusätzlich gezieltes Bereinigen des eigenen
Archivs nötig. Fremde lokale Bootstrap-Arbeiten sind unverändert ausgeschlossen.
