# Ankündigungshistorie – lokaler Stand 21.10.2026

App 0.23.3/97 ist auf dem persönlichen Pilot-Handy installiert, Registrierung
erhalten und durch den Nutzer biometrisch entsperrt. Bridge 0.10.13 (weiter
Schema 12) und Nextcloud-Adapter 0.3.4 sind am 21.10.2026 ausgerollt.
Kein OTA veröffentlicht. Einzelgeräteabnahme für Push, Ausblenden auf der
Startseite, Ablauf und bewusstes Löschen in Nextcloud erfolgreich abgeschlossen.

Tests: 415 Android-Debug- und 383 Release-Tests, keine Fehler; Release-Lint
0 Fehler/130 Warnungen. 176 Bridge-Tests im finalen Python-3.13-Container und
43 isolierte PHP-Prüfungen (14 bisherige Push-Verträge + 29 Historie/Migration)
bestanden. Zusätzlich: reale gezielte Migration nur der eigenen App auf
Nextcloud 35.0.2/MariaDB 11.8.6 erfolgreich, eigener Tabellen-/Retentionindex
vorhanden; ursprüngliche Announcement-Center-Tabellenschemata bytegleich.
Signierte Historienroute mit 14-Tage-Vertrag und leerem aktuellem Katalog
erfolgreich; ungültige UID auch mit gültiger E-Mail 404, unsigniert 401.
Handy lädt die native Historie per HTTP200 ohne Fehler; echte Ablauf- und
Quellenlöschungen wurden danach mit zwei neuen Nutzerhinweisen geprüft.

Nutzerfreigabe: Hinweise sollen nach Ausblenden auf der Startseite, zeitlichem
Ablauf und ausdrücklichem Löschen in Nextcloud in der Glocke nachlesbar bleiben.
Historie: 14 Tage ab Veröffentlichung. Noch aktive ältere Hinweise bleiben
weiterhin aktiv; nach deren späterem Löschen entsteht keine neue 14-Tage-Frist.

Die bestehende serverseitige Entdeckung sichert veröffentlichte Hinweise samt
Zielgruppen in einer eigenen, mit Nextcloud ICrypto verschlüsselten Tabelle
`ml_announcement_history`. Keine Änderung an Announcement Center, dessen
Tabellen oder Löschaufgaben. Unveränderte Inhalte werden nicht alle 15 Sekunden
neu geschrieben. Nach Fristablauf werden Archivzeilen beim nächsten laufenden
Entdeckungsdurchlauf entfernt und unabhängig davon bei jedem Abruf ausgefiltert.
Ankündigungen, die vor Aktivierung dieser Funktion bereits aus Nextcloud gelöscht
wurden, lassen sich dadurch nicht rückwirkend wiederherstellen.

Der neue signierte Nextcloud-Pfad
`/apps/missionleben_announcements/api/v1/announcement-history` und der enge
Bridge-GET `/v1/announcements/history` liefern eine frisch autorisierte Historie.
Aktiver Benutzer, eindeutige UID/E-Mail und aktuelle Nextcloud-Mitgliedschaften
bleiben Pflicht. Bei noch vorhandenen Posts gilt die aktuelle Quellen-Zielgruppe;
bei gelöschten Posts die archivierte Zielgruppe, aber weiterhin die aktuelle
Benutzermitgliedschaft. Keine Gruppenlisten in der Antwort, keine unsignierte
Historie und kein veralteter Cache als Fehler-Rückfall. Berechtigungsausfall oder
Profilwechsel leert die Historie auf dem Handy. Dort stehen nur freigegebene
Inhalte im entsperrten Arbeitsspeicher; kein neuer lokaler Inhaltscache.

Die Glocke kennzeichnet alte Hinweise als „Archiviert“. „Gelesen“ ändert nur
den lokalen Lesestatus und die Startseitenanzeige, nicht das Archiv. Historische
Inhalte werden niemals erneut auf die Startseite oder als Push übernommen.

Live-Pilot mit neuem Hinweis 20: verschlüsselt vor Ablauf erfasst, danach in der
nativen Glocke als „Archiviert“ samt Inhalt gelesen. Nextcloud hat anschließend
Quell- und Gruppenzeile tatsächlich entfernt (beide Anzahl0); frischer signierter
Historienabruf behält Inhalt/Archivstatus, aktiver Detailabruf liefert leer.
Die Frist entspricht exakt Veröffentlichungszeit+14Tage. Dieser erste Hinweis
war bei der automatischen Erkennung nur noch12Sekunden gültig und vor dem
nächsten15-Sekunden-Versanddurchlauf abgelaufen, daher keine sichtbare Push-Anzeige.

Zweiter Nutzerhinweis21: vom Nutzer für22:06Uhr terminiert, automatisch durch
den vorhandenen Nextcloud-Scheduler veröffentlicht, vom Server um20:06:49UTC
erkannt und um20:07:03UTC genau einmal zugestellt. Android-Push sichtbar im Kanal
„Ankündigungen“. „Gelesen“ blendet nur die Startseite aus, entfernt Badge/Tray
und behält den Inhalt unter der Glocke. Anschließend hat der Nutzer21 vor Ablauf
bewusst gelöscht: Quell-/Gruppenzeilen0, Archiv bleibt; frischer Abruf zeigt auf
dem Handy „Archiviert“ und weiterhin lesbaren Betreff/Inhalt.

Wachhaltung nach Test wieder auf ursprünglichen Wert0 gesetzt und verifiziert.
Bereits vor Aktivierung gelöschte Hinweise sind nicht rekonstruierbar. Reale
14Tage müssen nicht für diese Abnahme abgewartet werden: TTL-Grenzen sind
automatisiert geprüft und gespeicherte Fristen entsprechen Veröffentlichung+14Tage.
Mehrgeräte-/eingeschränkte Gruppenfälle wurden in diesem Einzelpilot nicht live
getestet. Noch keine OTA-Freigabe oder Veröffentlichung aus dieser Abnahme ableiten.

Quell-/Konfigurationssicherungen beider Hosts:
`/root/codex-backups/20261021-announcement-history`. Nextcloud: alte eigene
Appquellen, eigene appconfig-/migrations-Zeilen, ursprüngliche Quelltabellen-Schemata.
Bridge: geprüfte Online-SQLite-Sicherung, Compose, Quellen und Rückweg-Image
`mission-leben-device-bridge:before-history-20261021`. Nur Bridge neu erstellt;
andere Container unverändert. Bridge healthy, Schema12, quick_check ok, keine
FK-Verletzungen; Nextcloud wartungsfrei und kein ausstehendes DB-Upgrade.

Rückweg: vorzugsweise vorwärts korrigieren, keine alte Gesamt-Datenbank
zurückspielen. Alte Apps ignorieren den zusätzlichen Pfad. Bei vollständiger
Adapter-Rücknahme nur die neue eigene Archivtabelle gezielt bereinigen, damit
ohne den neuen Bereinigungsdurchlauf kein unbefristeter Datenbestand verbleibt.
Bestehende Bridge-Sicherheits-/Push-Schemata unverändert lassen.
