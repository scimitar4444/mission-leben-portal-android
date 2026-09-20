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
| Refresh-Nutzung verlängert die Anmeldung unbegrenzt | App erzwingt unabhängig von Authentiks Rotation eine absolute Grenze von `auth_time + 90 Tage`; danach werden Token und Webdaten entfernt |
| Angreifer nutzt den gespeicherten Anmeldenamen für passwortlosen Zugang | Der verkürzte 90-Tage-Flow läuft nur nach Prüfung von persönlichem Modus, Endpoint, Ablauf und direkter Benutzerbindung. Das Passwort entfällt ausschließlich, wenn derselbe Benutzer bereits ein bestätigtes TOTP besitzt; sonst wird das Passwort verlangt |
| anderes Gerät kopiert Geräte-ID | P-256-Private-Key ist nicht exportierbar; Server verlangt Besitznachweis |
| fremdes Gerät bestätigt eine Web-Anmeldung | Authentik ordnet den Benutzer der Anfrage zu; die Bridge liefert sie nur an persönliche Kommunikationsregistrierungen desselben OIDC-Subjects aus und verlangt eine frische P-256-Signatur sowie ein weiterhin gültiges Authentik-Device-Token |
| Bridge-/Netzfehler lässt eine Anmeldung versehentlich durch | die Duo-kompatible API liefert bei Fehler, Zeitablauf, unbekanntem Ergebnis oder fehlendem Gerät ausdrücklich `deny`; nur ein rechtzeitiges, einmaliges `approve` wird zu `allow` |
| App-Bestätigung erzeugt einen Anmelde-Loop | der eigene Android-OIDC-Flow enthält die zentrale App-Bestätigungsstufe nicht; sie ist nur eine Option in den zentralen Browser-Flows |
| vorheriger Benutzer bleibt auf Shared Tablet angemeldet | kein `offline_access`, kein persistenter AuthState, prominente End-Session-Abmeldung |
| unbekannte App wird sichtbar | Liste kommt aus policy-geprüfter Authentik-API |
| kompromittiertes Handy schickt Schad-URL an PC | nur `open_talk(room_token)`, Ziel-URL entsteht im Companion |
| ausgeschiedener Mitarbeiter nutzt App weiter | Authentik-User und Anwendungssitzungen zentral sperren, Refresh Token widerrufen und Gerätebindung sperren. Die Bridge sendet vor ihrer Datenbereinigung einen inhaltslosen FCM-Weckimpuls; die App prüft Authentik selbst, löscht bei negativer Prüfung Sitzung und Webdaten und schließt einen offenen internen Browser. Zusätzlich erfolgt die Prüfung vor jeder geschützten Web-App und bei Rückkehr ins Portal |
| Benutzer sieht eine Ankündigung für eine fremde Gruppe | Nextcloud löst serverseitig genau ein aktives Konto auf und filtert gegen dessen aktuelle Gruppen; die Bridge speichert nur dieses bereits gefilterte Ergebnis getrennt nach Authentik-Subject und gibt keine Gruppenbezeichnungen aus |
| entfernter Benutzer sieht eine alte zwischengespeicherte Ankündigung | jeder Abruf prüft zuerst das Access-Token live bei Authentik; Offboarding löscht den Subject-Cache. Änderungen nur an einer Nextcloud-Gruppenmitgliedschaft können innerhalb der fünfminütigen Frischzeit und bei Nextcloud-Ausfall bis zur markierten Ausfallgrenze nachwirken |
| fremder Dienst liest Ankündigungen aus Nextcloud | eigener HTTPS-Endpunkt ohne Schreiboperationen; HMAC über Methode, festen Pfad, Zeitstempel, Nonce und Body-Hash mit maximal 60 Sekunden Zeitabweichung; eigenes Secret nur in Nextcloud und Bridge |
| verlorenes Shared Tablet dient als zweiter Faktor | Authentik-Device nachvollziehbar mit `mission-leben.de/status=disabled`, Zeitpunkt und Grund sperren; die serverseitige Policy verweigert es und die App verwirft daraufhin Sitzung und Webdaten. Zusätzlich muss der Benutzer Mitglied der exakt zugeordneten `ORG_*`-Einrichtungsgruppe sein und sein Passwort eingeben |
| WebView lädt manipulierte Inhalte | nur HTTPS auf konfigurierten Domain-Endungen, kein Datei-/Content-Zugriff, kein Mixed Content, Safe Browsing und harte TLS-Fehlerbehandlung; die einzige JavaScript-Schnittstelle signiert nur Authentik-Endpoint-Challenges auf der exakten Authentik-Origin und gibt kein Token aus |
| Webseite greift unbemerkt auf Kamera/Mikrofon zu | der WebView verweigert jede Medienfreigabe unabhängig von Domain und Android-Berechtigung; `RECORD_AUDIO` ist nicht im Manifest, `CAMERA` ist ausschließlich für den nativen QR-Scanner vorhanden |
| vorheriger Benutzer hinterlässt Browserdaten | Cookie-Speicher, DOM-/Webspeicher, HTTP-Zugangsdaten, Cache, Formulardaten und App-Downloads werden bei Abmeldung/Profilwechsel gelöscht |
| veraltete Browserengine | Android System WebView wird separat aktualisiert; MDM muss Updates erzwingen und veraltete Geräte sperren |
| FCM oder ein fremder Push schleust Text, Schad-URL oder falschen Sperrbefehl ein | FCM enthält nur feste Aktionen sowie gegebenenfalls Ereignis-ID, Typ und Revision; App ignoriert freie Texte/URLs, öffnet ausschließlich eine passende Authentik-App und sperrt erst nach eigener negativer Live-Prüfung bei Authentik |
| Ereignis-ID wird abgegriffen | Detailabruf verlangt vertrauenswürdiges gebundenes Gerät, P-256-Signatur, Zeitfenster und einmalige Nonce |
| fremder QR-Code schleust eine URL oder einen Token ein | Scanner akzeptiert ausschließlich den exakten App-Deep-Link oder `https://geraete.mission-leben.de/install` mit Geheimnis im Fragment und genau einem plausiblen Token; QR-Auswertung und Übergabe bleiben lokal |
| Enrollment-QR wird kopiert | QR gilt wie der Authentik-Enrollment-Token als Geheimnis, wird nur zehn Minuten und modus-/principal-spezifisch angezeigt und nach erfolgreichem Einlösen serverseitig entwertet. Das URL-Fragment wird weder an Webserver noch Reverse Proxy übertragen. Beim persönlichen Gerät besteht die direkte Benutzerbindung bereits vor der QR-Ausgabe |
| QR-Code wird gleichzeitig mehrfach verwendet | Der Geräte-Einrichtungscontainer serialisiert Redeem-Anfragen in genau einem Worker, prüft den Authentik-Token live und löscht ihn vor der Antwort. Mehrere Container-Replikate sind ohne verteilte Sperre verboten |
| Leitung manipuliert Einrichtung oder Mitarbeiter-ID | Rollen IT, Zentrale, EL und PDL werden aus Authentik-Headern ermittelt; alle nicht globalen Rollen werden serverseitig nochmals gegen aktive `ORG_*`-Mitgliedschaften und Gruppenattribute geprüft |
| gestohlenes Kennwort öffnet die Geräte-Einrichtung | der anwendungsbezogene Authentik-Authorization-Flow verlangt zusätzlich ein bereits eingerichtetes TOTP oder einen Passkey und verweigert Benutzer ohne kompatiblen Faktor; die Seite bietet keine MFA-Einrichtung an |
| Mitarbeiter registriert das Gerät für eine andere Person | die Selbstregistrierung übernimmt den Benutzernamen ausschließlich aus den vom Authentik-Proxy gesetzten Headern, sucht exakt dieses aktive Konto und bindet die persönliche Device Access Group vor Ausgabe des Einmal-Links; es existiert keine Benutzerauswahl |
| alte Web-Sitzung registriert das Gerät für den vorherigen Benutzer | die App löscht vor jeder persönlichen Selbstregistrierung Cookies, Webspeicher, Cache, Formulardaten und geschützte Downloads; danach ist eine neue Authentik-Anmeldung erforderlich |
| Angreifer fälscht `X-Authentik-*`-Header | Der Container ist nur intern erreichbar; der Reverse Proxy entfernt eingehende Identitätsheader und setzt sie ausschließlich aus Authentik Forward Auth neu. Zusätzlich muss der erwartete Authentik-Anwendungs-Slug übereinstimmen |
| GitHub-Release oder OTA-Manifest wird manipuliert | App akzeptiert nur den fest verdrahteten Repositorypfad über HTTPS, einen höheren `versionCode`, die deklarierte Größe und SHA-256-Prüfsumme sowie ein APK mit identischem Paketnamen und demselben Android-Signaturzertifikat wie die installierte App |
| Android-Signierschlüssel wird entwendet | privater Schlüssel bleibt außerhalb von GitHub und der CI, liegt lokal nur zugriffsgeschützt vor und benötigt eine verschlüsselte Offline-Sicherung; bei Verdacht werden keine weiteren OTA-Releases veröffentlicht |
| Sperrbildschirm verrät Fachdaten | Android-Notification ist `PRIVATE` und besitzt eine neutrale öffentliche Version; Shared Tablets erzwingen `minimal` |
| Bridge-Datenbank wird kopiert | FCM-Installations-IDs und die für Live-Prüfungen benötigten Authentik-Device-Token sind mit AES-256-GCM verschlüsselt; Schlüssel liegt nur als Server-Secret vor |
| ausgeschiedener Mitarbeiter behält ein noch gültiges Gerätetoken | der Live-Status prüft bei persönlichen Geräten zusätzlich die eindeutige Authentik-Benutzerbindung und `is_active`; ein fünfminütiger, idempotenter Reconciler deaktiviert das Gerät und bereinigt die Bridge |
| Zimbra-Integrationskonto wird missbraucht | eigener Worker je Mailbox-Server, explizite Konto-ID-Liste, Secret-Datei, keine Benutzerkennwörter und begrenzte Suchabfragen; Rechte und Audit müssen vor Produktion geprüft werden |
| FCM-Zuordnung bleibt nach Abmeldung aktiv | App löscht die Zuordnung bestmöglich am Device Service; Server sperrt sie zusätzlich bei Offboarding oder Gerätesperre |
| Firebase-Dienstkonto wird kompromittiert | Dienstkonto nur im Server-Secret-Store, minimale Berechtigung, kein Schlüssel in Repository oder APK; Versand und Gerätezuordnung auditieren |

## Bewusste MVP-Grenzen

- Android-Key-Attestation ist im ersten MVP noch nicht an eine Server-Nonce gebunden. Die Freigabe beruht deshalb auf einem zeitlich begrenzten Authentik-Enrollment-Token plus Device Access Group und ersetzt kein MDM.
- Authentik Endpoint Devices ist Early Preview und hat keinen offiziellen Android-Agenten. Für den ausdrücklich gewünschten Pilot implementiert die App den in Authentik 2026.8.3 vorhandenen Agent-Enrollment-, Check-in- und Endpoint-Stage-Vertrag versionsgebunden.
- Die App ist kein MDM. Sie meldet Android-Version, Hersteller, Modell und App-Version als Authentik Device Facts; hardwaregestützte Konformitätsnachweise und Remote-Wipe bleiben Aufgabe eines MDM/Work Profiles.
- Der Webcontainer kann nur Daten löschen, wenn die App das Sperr-/Abmeldesignal erhält. Für ein ausgeschaltetes oder dauerhaft offline befindliches Privatgerät bleibt MDM-/Work-Profile-Wipe die belastbare Rückfallebene.
- WebView-Anmeldung ist für den kontrollierten Enterprise-Container bewusst gewählt. Externe Identitätsanbieter und nicht freigegebene Domains werden nicht eingebettet.
- Der Zimbra-Worker behandelt typische Mail- und Kalenderinstanzen. Serienausnahmen, Absagen und individuelle Erinnerungen müssen gegen Zimbra 10.1 mit realen Testkonten geprüft werden.
- Der Talk-Bot-Webhook ist implementiert, aber Bot-Aktivierung, Raum-/Benutzerzuordnung und Zustellung müssen gegen die installierte Nextcloud-/Talk-Version geprüft werden.
- SQLite ist nur für einen einzelnen Pilotcontainer vorgesehen; Hochverfügbarkeit benötigt PostgreSQL und eine gemeinsame Job-Queue.
- Der Nextcloud-Ankündigungsadapter greift versionsgebunden auf die Tabellen des installierten Announcement Centers zu. Nach Nextcloud-/App-Updates müssen Signatur-, Benutzerauflösungs- und Gruppenfiltertest vor der Freigabe erneut laufen.
