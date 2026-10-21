# Ankündigungshistorie – lokaler Stand 21.10.2026

App 0.23.3/97 ist auf dem persönlichen Pilot-Handy installiert, Registrierung
erhalten und durch den Nutzer biometrisch entsperrt. Bridge 0.10.13 (weiter
Schema 12) und Nextcloud-Adapter 0.3.4 sind am 21.10.2026 ausgerollt.
Kein OTA veröffentlicht; Ablauf-/Löschtest mit neuem echten Hinweis steht aus.

Tests: 415 Android-Debug- und 383 Release-Tests, keine Fehler; Release-Lint
0 Fehler/130 Warnungen. 176 Bridge-Tests im finalen Python-3.13-Container und
43 isolierte PHP-Prüfungen (14 bisherige Push-Verträge + 29 Historie/Migration)
bestanden. Zusätzlich: reale gezielte Migration nur der eigenen App auf
Nextcloud 35.0.2/MariaDB 11.8.6 erfolgreich, eigener Tabellen-/Retentionindex
vorhanden; ursprüngliche Announcement-Center-Tabellenschemata bytegleich.
Signierte Historienroute mit 14-Tage-Vertrag und leerem aktuellem Katalog
erfolgreich; ungültige UID auch mit gültiger E-Mail 404, unsigniert 401.
Handy lädt die native Historie per HTTP200 ohne Fehler. Das ist noch keine
Abnahme der Aufbewahrung nach echtem Ablauf oder Quellenlöschung.

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

Für die letzte Abnahme: neuen Hinweis vor Ablauf/Löschung erfassen, auf dem Handy
als gelesen aus der Startseite ausblenden, Ablauf und Quellenlöschung prüfen und
Inhalt in der Glocke nachlesen. Bereits vor Aktivierung gelöschte Hinweise sind
nicht rekonstruierbar. Vor dieser tatsächlichen Geräteabnahme kein OTA.

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
