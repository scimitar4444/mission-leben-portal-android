# Bedrohungsmodell

## Geschützte Werte

- Authentik Access-/Refresh Token
- nicht exportierbarer Geräteschlüssel
- Zuordnung Benutzer ↔ persönliches Gerät
- Trust-Status gemeinsamer Tablets
- Liste der für einen Benutzer freigegebenen Anwendungen und Geräte
- Talk-Raumtoken während einer Übergabe

## Wesentliche Gegenmaßnahmen

| Risiko | Gegenmaßnahme |
|---|---|
| APK wird dekompiliert | Public OIDC Client ohne Client-Secret |
| Refresh Token wird aus App-Daten kopiert | zweistufige Envelope-Verschlüsselung; Master-Key im Android Keystore, Freigabe nur per Biometrie/Gerätecode |
| anderes Gerät kopiert Geräte-ID | P-256-Private-Key ist nicht exportierbar; Server verlangt Besitznachweis |
| vorheriger Benutzer bleibt auf Shared Tablet angemeldet | kein `offline_access`, kein persistenter AuthState, prominente End-Session-Abmeldung |
| unbekannte App wird sichtbar | Liste kommt aus policy-geprüfter Authentik-API |
| kompromittiertes Handy schickt Schad-URL an PC | nur `open_talk(room_token)`, Ziel-URL entsteht im Companion |
| ausgeschiedener Mitarbeiter nutzt App weiter | Authentik-User und Anwendungssitzungen zentral sperren, Refresh Token widerrufen, Gerätebindung sperren und lokales Löschsignal auslösen |
| verlorenes Shared Tablet umgeht MFA | Gerätebindung in Authentik sperren; Trust ist serverseitig, nicht nur lokaler Status |
| WebView lädt manipulierte Inhalte | nur HTTPS auf konfigurierten Domain-Endungen; kein JavaScript-Bridge, kein Datei-/Content-Zugriff, kein Mixed Content, Safe Browsing und harte TLS-Fehlerbehandlung |
| Webseite greift unbemerkt auf Kamera/Mikrofon zu | nur explizit bekannte WebRTC-Ressourcen, erlaubte HTTPS-Origin und Android-Laufzeitfreigabe |
| vorheriger Benutzer hinterlässt Browserdaten | Cookie-Speicher, DOM-/Webspeicher, HTTP-Zugangsdaten, Cache, Formulardaten und App-Downloads werden bei Abmeldung/Profilwechsel gelöscht |
| veraltete Browserengine | Android System WebView wird separat aktualisiert; MDM muss Updates erzwingen und veraltete Geräte sperren |

## Bewusste MVP-Grenzen

- Android-Key-Attestation ist im ersten MVP noch nicht an eine Server-Nonce gebunden. Deshalb darf Enrollment nie automatisch `trusted` ergeben.
- Authentik Endpoint Devices ist Early Preview. Der Device Service muss gegen die konkret installierte Version getestet werden.
- Die App ist kein MDM. Gerätekonformität wie Patchstand oder Verschlüsselung wird nur ausgewertet, wenn der Device Service verifizierbare Daten erhält.
- Der Webcontainer kann nur Daten löschen, wenn die App das Sperr-/Abmeldesignal erhält. Für ein ausgeschaltetes oder dauerhaft offline befindliches Privatgerät bleibt MDM-/Work-Profile-Wipe die belastbare Rückfallebene.
- WebView-Anmeldung ist für den kontrollierten Enterprise-Container bewusst gewählt. Externe Identitätsanbieter und nicht freigegebene Domains werden nicht eingebettet.
- Hintergrundmeldungen für Mail, Termine und Talk benötigen den in `NOTIFICATIONS.md` beschriebenen Ereignis- und Push-Dienst; WebView allein reicht dafür nicht.
