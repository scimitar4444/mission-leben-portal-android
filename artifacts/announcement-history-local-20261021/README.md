# Ankündigungshistorie – lokaler Stand 21.10.2026

Lokal umgesetzt und getestet: App 0.23.3/97, Bridge 0.10.13 (weiter Schema 12), Nextcloud-Adapter 0.3.4.
Noch nicht ausgerollt oder als OTA veröffentlicht.

Tests: 415 Android-Debug- und 383 Release-Tests, keine Fehler; Release-Lint
0 Fehler/130 Warnungen. 176 Bridge-Tests im finalen Python-3.13-Container und
43 isolierte PHP-Prüfungen (14 bisherige Push-Verträge + 29 Historie/Migration)
bestanden. Reale Datenbankmigration, signierte Historienroute und Handyabnahme
sind damit ausdrücklich noch nicht nachgewiesen.

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

Vor Ausbringung: eigene Nextcloud-App gezielt auf 0.3.4 migrieren (nur neue
eigene Tabelle), HMAC-/UID-/Gruppen-/TTL-Prüfung; Archiveintrag vor Ablauf oder
Löschung erfassen und danach echten Leseabruf prüfen. Server nicht ohne Sicherung
oder parallel zu Nextcloud-Updatearbeiten ändern. Danach Bridge 0.10.13, dann
gleich signierte Test-App 0.23.3 und tatsächliche Geräteabnahme vor OTA.

Rückweg: vorzugsweise vorwärts korrigieren, keine alte Gesamt-Datenbank
zurückspielen. Alte Apps ignorieren den zusätzlichen Pfad. Bei vollständiger
Adapter-Rücknahme nur die neue eigene Archivtabelle gezielt bereinigen, damit
ohne den neuen Bereinigungsdurchlauf kein unbefristeter Datenbestand verbleibt.
Bestehende Bridge-Sicherheits-/Push-Schemata unverändert lassen.
