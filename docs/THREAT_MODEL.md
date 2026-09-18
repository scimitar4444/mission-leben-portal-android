# Bedrohungsmodell

## Geschützte Werte

- Authentik Access-/Refresh Token
- nicht exportierbarer Geräteschlüssel
- Zuordnung Benutzer ↔ persönliches Gerät
- Trust-Status gemeinsamer Tablets
- Liste der für einen Benutzer freigegebenen Anwendungen und Geräte
- Talk-Raumtoken während einer Übergabe
- Firebase-Installations-ID und ihre Benutzer-/Gerätezuordnung
- Absender, Betreff, Terminzeit/-ort und Talk-Vorschau in der Bridge

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
| verlorenes Shared Tablet umgeht MFA | Gerät in der Bridge sperren; die Pilot-Gerätefreigabe ersetzt Authentik-MFA nicht |
| WebView lädt manipulierte Inhalte | nur HTTPS auf konfigurierten Domain-Endungen; kein JavaScript-Bridge, kein Datei-/Content-Zugriff, kein Mixed Content, Safe Browsing und harte TLS-Fehlerbehandlung |
| Webseite greift unbemerkt auf Kamera/Mikrofon zu | nur explizit bekannte WebRTC-Ressourcen, erlaubte HTTPS-Origin und Android-Laufzeitfreigabe |
| vorheriger Benutzer hinterlässt Browserdaten | Cookie-Speicher, DOM-/Webspeicher, HTTP-Zugangsdaten, Cache, Formulardaten und App-Downloads werden bei Abmeldung/Profilwechsel gelöscht |
| veraltete Browserengine | Android System WebView wird separat aktualisiert; MDM muss Updates erzwingen und veraltete Geräte sperren |
| FCM oder ein fremder Push schleust Text oder Schad-URL ein | FCM enthält nur Ereignis-ID, Typ und Revision; App ignoriert freie Texte/URLs und öffnet ausschließlich eine passende Authentik-App |
| Ereignis-ID wird abgegriffen | Detailabruf verlangt vertrauenswürdiges gebundenes Gerät, P-256-Signatur, Zeitfenster und einmalige Nonce |
| Sperrbildschirm verrät Fachdaten | Android-Notification ist `PRIVATE` und besitzt eine neutrale öffentliche Version; Shared Tablets erzwingen `minimal` |
| Bridge-Datenbank wird kopiert | FCM-Installations-IDs sind mit AES-256-GCM verschlüsselt; Schlüssel liegt nur als Server-Secret vor |
| Zimbra-Integrationskonto wird missbraucht | eigener Worker je Mailbox-Server, explizite Konto-ID-Liste, Secret-Datei, keine Benutzerkennwörter und begrenzte Suchabfragen; Rechte und Audit müssen vor Produktion geprüft werden |
| FCM-Zuordnung bleibt nach Abmeldung aktiv | App löscht die Zuordnung bestmöglich am Device Service; Server sperrt sie zusätzlich bei Offboarding oder Gerätesperre |
| Firebase-Dienstkonto wird kompromittiert | Dienstkonto nur im Server-Secret-Store, minimale Berechtigung, kein Schlüssel in Repository oder APK; Versand und Gerätezuordnung auditieren |

## Bewusste MVP-Grenzen

- Android-Key-Attestation ist im ersten MVP noch nicht an eine Server-Nonce gebunden. Deshalb darf Enrollment nie automatisch `trusted` ergeben.
- Authentik Endpoint Devices ist Early Preview und hat keinen offiziellen Android-Agenten. Der Pilot verwendet deshalb einen eigenen Gerätestatus und keine undokumentierten Agent-Protokolle.
- Die App ist kein MDM. Gerätekonformität wie Patchstand oder Verschlüsselung wird nur ausgewertet, wenn der Device Service verifizierbare Daten erhält.
- Der Webcontainer kann nur Daten löschen, wenn die App das Sperr-/Abmeldesignal erhält. Für ein ausgeschaltetes oder dauerhaft offline befindliches Privatgerät bleibt MDM-/Work-Profile-Wipe die belastbare Rückfallebene.
- WebView-Anmeldung ist für den kontrollierten Enterprise-Container bewusst gewählt. Externe Identitätsanbieter und nicht freigegebene Domains werden nicht eingebettet.
- Der Zimbra-Worker behandelt typische Mail- und Kalenderinstanzen. Serienausnahmen, Absagen und individuelle Erinnerungen müssen gegen Zimbra 10.1 mit realen Testkonten geprüft werden.
- Der Talk-Bot-Webhook ist implementiert, aber Bot-Aktivierung, Raum-/Benutzerzuordnung und Zustellung müssen gegen die installierte Nextcloud-/Talk-Version geprüft werden.
- SQLite ist nur für einen einzelnen Pilotcontainer vorgesehen; Hochverfügbarkeit benötigt PostgreSQL und eine gemeinsame Job-Queue.
