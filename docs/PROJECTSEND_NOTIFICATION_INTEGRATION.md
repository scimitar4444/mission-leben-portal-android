# ProjectSend-Dokumentenhinweise

Stand: 30.09.2026. Der ProjectSend-Helfer läuft außerhalb des ProjectSend-Cores und
bleibt bis zur geprüften Bridge- und App-Auslieferung ausgeschaltet.

## Vertrag

- Quelle: der getrennte Helper auf `projectsend-ml` (`172.16.40.143`).
- Ziel: `POST https://id.mission-leben.de/device-bridge/internal/v1/events`.
- Der Nginx-Eingang lässt nur diese Quell-IP und `POST` zu; die Bridge prüft
  zusätzlich eine ausschließlich für ProjectSend verwendete HMAC-SHA256-Signatur
  mit einem 120-Sekunden-Zeitfenster.
- Der JSON-Körper enthält ausschließlich `source_event_id` (`notification:<ID>`),
  den festgelegten OIDC-`issuer`, `user_subject` (64-stelliges Hex-`sub`) und
  `event_type: open_documents`. Er enthält weder Dateiname noch Inhalt oder URL.
- Die Bridge erzeugt selbst den festen Hinweis „Ein neues Dokument liegt in
  deinem persönlichen Postfach.“ und öffnet nur die berechtigte ProjectSend-Kachel
  aus dem Authentik-Katalog (`projectsend-ml-dokumente-test`).
- Mehrfachlieferungen derselben Quell-ID sind idempotent. Der Helper wertet
  `202` mit `created:false` als bestätigte Wiederholung.

## Empfänger- und Versionsgrenze

Die Bridge stellt Dokumentenereignisse nur an aktive, persönlich registrierte
Geräte mit Authentik-`enrollment_profile=personal-employee` und App-Version ab
`0.21.0` zu. Ein `shared-account-handset` ist trotz technischem `mode=personal`
ausgeschlossen; gemeinsame Tablets ebenfalls. Ältere Apps erhalten keinen ihnen
unbekannten Push. Noch gültige Ereignisse werden nach Registrierung der
kompatiblen App höchstens einmal je Gerät nachgereicht; die Aufbewahrung ist auf
sieben Tage begrenzt. Der ProjectSend-Helper filtert bereits an der Quelle auf
genehmigte, aktive persönliche Konten; die Bridge erzwingt ihre Gerätegrenze
zusätzlich.

Die SQLite-Migration erweitert ausschließlich die `notification_events`-Prüfung
auf `open_documents` (Schema v10). Vorhandene Event-IDs und Zustell-Fremdschlüssel
bleiben erhalten; `calendar_snapshots` aus Schema v9 werden nicht verändert.

## Aktivierungsreihenfolge

1. App 0.21.0 signiert veröffentlichen; Zertifikat, `versionCode`, Manifest,
   öffentliches APK und OTA-Pfad vergleichen. Mindestens ein kompatibles
   persönliches Gerät muss die Version nachweislich registriert haben.
2. Vor Bridge-Start eine konsistente SQLite-Sicherung sowie Kopien der
   auszutauschenden Dateien und das derzeitige Container-Image für den Rückweg
   auf dem Host vorhalten. Nur die geprüften Bridge-Dateien aus dem aktuellen
   Kalender-Stand ausbringen.
3. Das separate HMAC-Secret als root:10001/0440 unter
   `/opt/mission-leben-bridge/secrets/projectsend-hmac-secret` bereitstellen,
   ohne es in Logs oder Chat auszugeben. Bridge mit
   `compose.device-pilot.yml` plus opt-in `compose.projectsend.yml` starten.
4. Die exakte Nginx-Location ergänzen, `nginx -t` und Health prüfen. Von anderen
   Quell-IPs muss der Pfad gesperrt sein; der Helper bleibt noch aus.
5. Auf dem Dokumentenserver die empfängerlose `--bridge-probe` ausführen:
   gültige Signatur mit leerem Testkörper ergibt HTTP 400, ungültige Signatur
   HTTP 401. Erst danach den Helper starten und dessen Health prüfen.
6. Eine echte Push-Probe nur mit ausdrücklich benanntem Testempfänger und
   freigegebenem Testdokument durchführen. Browser-/Geräteannahme getrennt von
   API- und Container-Health protokollieren.

## Rückweg

Den ProjectSend-Helper zuerst stoppen. Die vorige Bridge-Version aus dem
zurückgehaltenen Image und den Dateikopien wiederherstellen. Da Schema v10
`open_documents` enthält, ist für eine Rückkehr zu einem v9-Binary die
vorherige konsistente SQLite-Sicherung erforderlich; zwischenzeitlich
eingetroffene Ereignisse würden dabei verloren gehen und müssen separat
abgeglichen werden. Die getrennte ProjectSend-Nginx-Location und das opt-in
Compose-Overlay können anschließend entfernt werden. Keinen produktiven
Rollback ohne erneute Ereignis- und Sicherungsprüfung ausführen.
