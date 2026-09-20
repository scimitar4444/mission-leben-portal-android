# Eigenen ntfy-Pushdienst betreiben

Die App verwendet kein Firebase Cloud Messaging. `push.mission-leben.de` zeigt auf einen eigenen ntfy-Container. Die Bridge bleibt die einzige Autorität für Benutzer-, Geräte- und Inhaltsprüfung; ntfy transportiert nur inhaltslose Wecksignale.

## Sicherheitsaufteilung

- ntfy hat `auth-default-access=deny-all`; anonyme Leser und Schreiber erhalten HTTP 403.
- Jedes Gerät bekommt ein zufälliges Topic und zwei eigene ntfy-Benutzer.
- Das Lesetoken kann nur dieses Topic lesen und liegt verschlüsselt im Android Keystore.
- Das Schreibtoken kann nur dieses Topic beschreiben und liegt ausschließlich AES-256-GCM-verschlüsselt in der Bridge.
- Topic und Token werden niemals aus Benutzername, E-Mail oder Authentik-Subject gebildet.
- ntfy-Nachrichten enthalten nur `action`, zufällige Ereignis-/Anfrage-ID, Typ und Revision. Details lädt die App danach P-256-signiert aus der Bridge.

## Server

`bridge/compose.device-pilot.yml` startet ntfy auf `127.0.0.1:2586` und die Bridge auf `127.0.0.1:8080`. Der ntfy-Datenordner ist nur für UID/GID 10001 beschreibbar. Der öffentliche Reverse Proxy leitet ausschließlich HTTPS für `push.mission-leben.de` auf Port 2586 weiter.

Die Bridge erhält:

```dotenv
BRIDGE_NTFY_PUBLIC_BASE_URL=https://push.mission-leben.de
BRIDGE_NTFY_INTERNAL_BASE_URL=http://ntfy:2586
BRIDGE_NTFY_AUTH_FILE=/ntfy/user.db
BRIDGE_NTFY_BINARY=/usr/local/bin/ntfy
```

Der App-Build verwendet standardmäßig:

```properties
ML_NTFY_PUBLIC_BASE_URL=https://push.mission-leben.de
```

Die App akzeptiert vom Bridge-Endpunkt keine abweichende Pushbasis. HTTP, Zugangsdaten in der URL, fremde Hosts sowie syntaktisch ungültige Topics oder Tokens werden verworfen.

## Android

Nach der Android-Benachrichtigungsfreigabe registriert die App das bereits von Authentik bestätigte Gerät bei der Bridge. Ein `remoteMessaging`-Foreground-Service hält eine einzelne HTTP-Stream-Verbindung. Die notwendige stille Dauerbenachrichtigung lautet „Mission Leben – sicher verbunden“. Bei Netzwechseln verbindet sich die App mit begrenztem exponentiellem Backoff neu; nach Neustart und App-Update startet der Dienst erneut.

Bei HTTP 401/403 verwirft die App das ntfy-Lesetoken. Eine geöffnete und angemeldete App registriert den Kanal erneut. Eine Sperrmeldung führt nie allein zum Löschen: Die App prüft zuerst den Endpoint live bei Authentik.

## Funktionstest

1. `https://push.mission-leben.de/v1/health` muss mit gültigem TLS HTTP 200 liefern.
2. Anonymes Lesen und Schreiben auf einem beliebigen Topic muss HTTP 403 liefern.
3. Gerät anmelden, Benachrichtigungen erlauben und die stille Verbindungsanzeige prüfen.
4. Bridge-Datensatz auf Provider `ntfy`, zufälliges Topic und getrennte Reader-/Writer-Namen prüfen, ohne Tokens auszugeben.
5. Mail-, Termin- oder Talk-Testereignis einspeisen und prüfen, dass ntfy nur ID, Typ und Revision sieht.
6. Detailanzeige und Öffnen ausschließlich der passenden Authentik-App prüfen.
7. Eine Anmeldeanfrage auslösen: ntfy enthält nur `action` und `request_id`; Bestätigen/Ablehnen erfolgt erst in der entsperrten App.
8. Testgerät in Authentik sperren: `refresh_security_state` weckt die App, die negative Authentik-Prüfung löscht Sitzung und Webdaten; ntfy-Reader und -Writer werden anschließend widerrufen.
9. Abmelden und prüfen, dass die Bridge beide ntfy-Benutzer und die lokale App das Lesetoken entfernt.

Für ein ausgeschaltetes oder dauerhaft offline befindliches Privatgerät ist ein garantiertes Remote-Wipe weiterhin Aufgabe von MDM beziehungsweise Android Work Profile.
