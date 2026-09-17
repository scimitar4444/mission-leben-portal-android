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
| ausgeschiedener Mitarbeiter nutzt App weiter | Authentik-User deaktivieren, Refresh Token widerrufen, persönliche Gerätebindung sperren |
| verlorenes Shared Tablet umgeht MFA | Gerätebindung in Authentik sperren; Trust ist serverseitig, nicht nur lokaler Status |
| Embedded WebView stiehlt Zugangsdaten | Anmeldung und App-Start erfolgen über Systembrowser/Custom Tabs |

## Bewusste MVP-Grenzen

- Android-Key-Attestation ist im ersten MVP noch nicht an eine Server-Nonce gebunden. Deshalb darf Enrollment nie automatisch `trusted` ergeben.
- Authentik Endpoint Devices ist Early Preview. Der Device Service muss gegen die konkret installierte Version getestet werden.
- Die App ist kein MDM. Gerätekonformität wie Patchstand oder Verschlüsselung wird nur ausgewertet, wenn der Device Service verifizierbare Daten erhält.
- Eine vollständig browserfreie biometrische SSO-Übergabe an jede Web-App benötigt einen zusätzlichen serverseitigen Session-Bootstrap. Der MVP schützt den nativen App-Token und nutzt für Web-Apps weiterhin den sicheren Systembrowser.
