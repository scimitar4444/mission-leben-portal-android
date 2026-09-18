# Device Service API v1

Der Device Service ist als Container unter `bridge/` implementiert. Authentik bleibt Quelle für Benutzeridentität und App-Berechtigungen. Die Bridge verwaltet die Android-spezifische Geräteidentität, Freigabe, Push-Zuordnung und Benachrichtigungsdetails.

Authentik Endpoint Devices ist weiterhin Early Preview und hat keinen offiziellen Android-Agenten. Die Bridge gibt deshalb nicht vor, ein interner Authentik-Agent zu sein. Eine spätere, dokumentierte Authentik-Anbindung kann hinter diesem stabilen App-Vertrag ergänzt werden.

Alle produktiven Endpunkte verwenden HTTPS. Enrollment-Tokens sind einmalig, kurzlebig und werden serverseitig nur als HMAC gespeichert.

## Gerät registrieren

```http
POST /v1/enrollments
Content-Type: application/json

{
  "enrollment_token": "…",
  "mode": "personal",
  "device_name": "Google Pixel Tablet",
  "platform": "android",
  "os_version": "13",
  "app_version": "0.5.2",
  "key_id": "…",
  "public_key_jwk": {
    "kty": "EC",
    "crv": "P-256",
    "kid": "…",
    "x": "…",
    "y": "…"
  }
}
```

Antwort:

```json
{
  "device_id": "c8af2e31-…",
  "status": "pending"
}
```

Der private P-256-Schlüssel wird im Android Keystore erzeugt und verlässt das Gerät nie. Die Bridge rekonstruiert den öffentlichen Schlüssel, prüft den `key_id`-Fingerabdruck und verbraucht den Enrollment-Code atomar.

## Administrative Gerätefreigabe

Diese Endpunkte dürfen nur intern beziehungsweise hinter einem Admin-Proxy erreichbar sein und verlangen `X-ML-Admin-Key`.

```http
POST /admin/v1/enrollment-tokens
X-ML-Admin-Key: …
Content-Type: application/json

{"mode":"personal","ttl_seconds":900,"auto_trust":false}
```

```http
GET /admin/v1/devices
X-ML-Admin-Key: …
```

```http
POST /admin/v1/devices/{device_id}/status
X-ML-Admin-Key: …
Content-Type: application/json

{"status":"trusted"}
```

Zulässige Zustände sind `pending`, `trusted` und `blocked`. Jeder Wechsel weg von `trusted` löscht die Push-Zuordnung. Bei einem persönlichen Gerät bindet die erste erfolgreiche Push-Anmeldung das freigegebene Gerät dauerhaft an das Authentik-Subject; ein anderer Benutzer wird danach abgewiesen.

Die App fragt den aktuellen Status mit derselben Keystore-Signatur wie Benachrichtigungsdetails ab:

```http
GET /v1/devices/{device_id}/status
X-ML-Key-ID: …
X-ML-Timestamp: …
X-ML-Nonce: …
X-ML-Signature: …
```

Solange ein konfiguriertes Gerät nicht `trusted` ist, bleibt die Authentik-Anmeldung in der App gesperrt. Bei `blocked` widerruft die App ihre OIDC-Sitzung, entfernt den lokalen Tresor, Hinweise und WebView-Daten. Die zentrale Kontosperre beziehungsweise MDM bleibt dennoch die maßgebliche zweite Sperrebene.

## Freigegebene Link-Ziele

```http
GET /v1/link-targets?capability=open_talk
Authorization: Bearer <authentik-user-access-token>
```

Die Bridge prüft das Token live am Authentik-UserInfo-Endpunkt und liefert nur fest konfigurierte Talk-Ziele. Sie gibt keine globale Authentik-Geräteliste aus.

## FCM-Installation einem Gerät zuordnen

```http
PUT /v1/push/registrations/{device_id}
Authorization: Bearer <authentik-user-access-token>
Content-Type: application/json

{
  "provider": "fcm",
  "installation_id": "…",
  "mode": "personal",
  "notification_privacy": "standard",
  "app_version": "0.5.2"
}
```

Die Installations-ID wird mit AES-256-GCM verschlüsselt gespeichert. `standard` liefert Titel und Zusammenfassung, `detailed` zusätzlich eine kurze Vorschau, `minimal` nur den neutralen lokalen Hinweis. Shared Tablets werden server- und clientseitig immer auf `minimal` reduziert.

Bei Abmeldung oder Profil-Reset:

```http
DELETE /v1/push/registrations/{device_id}
Authorization: Bearer <authentik-user-access-token>
```

## Ereignis von Zimbra oder Nextcloud

```http
POST /internal/v1/events
X-ML-Source: zimbra
X-ML-Timestamp: 1789682400
X-ML-Signature: …
Content-Type: application/json

{
  "source_event_id": "mail:account-id:message-id",
  "user_subject": "authentik-oidc-sub",
  "event_type": "open_mail",
  "title": "Absender",
  "summary": "Betreff",
  "preview": "Kurze Vorschau",
  "display_at": "2026-09-18T10:00:00Z",
  "expires_at": "2026-09-25T10:00:00Z"
}
```

Die Quellanfrage ist über Zeitstempel, Body-Hash und HMAC signiert. Ereignisse werden anhand `source + source_event_id` dedupliziert. Für Termine kann `deliver_at` gesetzt werden; die Bridge versendet erst zu diesem Zeitpunkt.

FCM enthält ausschließlich:

```json
{
  "action": "fetch_notification",
  "event_id": "opaque-id",
  "event_type": "open_mail",
  "revision": "1"
}
```

## Signierter Detailabruf

```http
GET /v1/notifications/{event_id}
X-ML-Device-ID: …
X-ML-Key-ID: …
X-ML-Timestamp: 1789682400
X-ML-Nonce: …
X-ML-Signature: …
```

Die App signiert mit `SHA256withECDSA` diese kanonische Form:

```text
ML-DEVICE-V1
GET
/v1/notifications/{event_id}
{device_id}
{key_id}
{timestamp}
{nonce}
{sha256(body)}
```

Die Bridge prüft zusätzlich:

- Zeitabweichung maximal 120 Sekunden,
- Nonce noch nie verwendet,
- öffentlicher Schlüssel und `key_id` passen,
- Gerät ist `trusted`,
- Benutzer ist aktiv,
- Ereignis gehört zur aktuellen Benutzer-Geräte-Bindung,
- Datenschutzstufe erlaubt Details,
- Ereignis ist noch nicht abgelaufen.

Der Sperrbildschirm erhält immer eine neutrale öffentliche Version. Die Detailantwort wird mit `Cache-Control: no-store` ausgeliefert.

## Talk öffnen

```http
POST /v1/handoffs
Authorization: Bearer <authentik-user-access-token>
Content-Type: application/json

{
  "target_device_id": "vk-ffm-01",
  "action": "open_talk",
  "room_token": "AbCDef123",
  "expires_in": 30
}
```

Nur feste Zielgeräte, `open_talk`, ein syntaktisch gültiger Raumtoken und maximal 30 Sekunden TTL werden akzeptiert. Eine vollständige URL, ein Benutzerkennwort, ein Shell-Befehl oder ein beliebiges URI-Schema ist nicht zulässig.
