## Änderung

0.23.2 / versionCode 96: eigener Ankündigungs-Push, getrennte Startseitenbereiche und direkt erreichbarer Gelesen-Knopf. App, Bridge 0.10.12/Schema 12 und Nextcloud-Adapter 0.3.3 bilden einen engen, versionierten Vertrag. Backend bereits gezielt ausgerollt und live geprüft; alte App-Versionen erhalten die neue Aktion nicht.

## Sicherheitsprüfung

Implementierender Agent mit Quellprüfung und Negativtests, kein unabhängiges menschliches Review. Neue Aktion öffnet nur die native Glocke nach normaler Anmeldung. HMAC bindet Pfad, Timestamp, Nonce und Body. Aktuelle aktive Nextcloud-Identität und Zielgruppen werden bei Auflösung, Zustellung und Detailabruf geprüft. Explizit fehlende UID fällt nicht auf eine andere E-Mail-Identität zurück. Quellfehler pausieren den Ankündigungsweg ohne normale Kanäle zu blockieren. ntfy enthält nur Ereignis-Metadaten, keinen Inhalt. Keine neue Android-Berechtigung; Shared-Privatsphäre, Biometrie, MFA und 90-Tage-Grenze unverändert. Fremde lokale Authentik-Bootstrap-Arbeiten sind nicht Teil dieses PR.

## Prüfung

- Android: 405 Debug- und 375 Release-Tests, keine Fehler; Release-Lint 0 Fehler/130 Warnungen.
- Bridge: 170 Tests im finalen Python-3.13-Container; 14 isolierte PHP-Vertragsprüfungen.
- Signierte Live-Routen, unbekannte Identität, fehlende explizite UID, unsignierter Zugriff (401), SQLite-Schema/Integrität/FKs und Baseline ohne Altversand geprüft.
- Persönliches Testhandy: bestehende Registrierung erhalten. Frische Veröffentlichung automatisch erkannt, exakt eine Zustellung und Detailabruf, eigener Android-Kanal, tatsächliches Antippen öffnet Glocke. Auf-/Zuklappen, Gelesen entfernt Startseitenkarte und erhält gelesenen Eintrag in Glocke.
- Gleichzeitige Agenda-/Hinweis-Anzeige in synthetischen Layouts geprüft. Mehrere Handys, eingeschränkte Zielgruppe und Netzunterbrechung in diesem Pilot nicht zusätzlich live geprüft. Bereits sichtbare Android-Meldung wird bei Quell-Löschung nicht fern-zurückgerufen.

## Rückweg

Backend-Flag abschalten und nur Bridge neu erstellen, aktuelle DB behalten. Vor altem Bridge-Image die ausschließlich neuen Ankündigungsereignisse gezielt samt Zustellreferenzen entfernen; kein altes Gesamt-DB-Restore. Android nur gleich signiert vorwärts korrigieren; keine automatische Deinstallation oder Herabstufung. OTA-Artefakte und öffentliche Downloads werden separat geprüft.
