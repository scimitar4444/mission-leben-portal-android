# Communication Bridge API v1

Die Bridge unter `bridge/` ist ausschliesslich fuer Kommunikation und Signalisierung zustaendig. Authentik ist die alleinige Quelle fuer Device, Connection, Device Token, Fakten, Ablauf, Device Access Group, Benutzeridentitaet und App-Berechtigungen.

Insbesondere besitzt die Bridge keine Enrollment-, Geraetestatus- oder Admin-Freigabe-API. Die frueheren Routen `/v1/enrollments`, `/v1/devices/*`, `/admin/v1/enrollment-tokens` und `/admin/v1/devices` existieren nicht.

## Funktionsberechtigungen

```http
GET /v1/capabilities
Authorization: Bearer <Authentik-OIDC-Access-Token>
```

Die Antwort enthaelt nur bekannte, in Authentik erteilte Capabilities. `open_talk` wird aus `ENT_TALK_RAUMUEBERGABE`, `device_profile_switch` aus `ENT_DEVICE_PROFILE_SWITCH` abgeleitet. `GET /v1/link-targets?capability=open_talk` und `POST /v1/handoffs` verweigern den Zugriff ohne `open_talk` serverseitig.

## Direkter Authentik-Geraetevertrag

Die Android-App spricht fuer den Geraetelebenszyklus direkt mit Authentik:

```http
POST /api/v3/endpoints/agents/connectors/enroll/
Authorization: Bearer <Authentik-EnrollmentToken>
```

```http
GET https://geraete.mission-leben.de/api/v1/devices/status
Authorization: Bearer+Agent <Authentik-DeviceToken>
```

```http
POST /api/v3/endpoints/agents/connectors/check_in/
Authorization: Bearer+Agent <Authentik-DeviceToken>
```

Das Device Token wird auf Android mit einem nicht exportierbaren AES-256-GCM-Schluessel aus dem Android Keystore verschluesselt. Die App verwendet es ausserdem zum Beantworten der Authentik Endpoint-Stage-Challenge. Eine nachgelagerte Authentik-Policy prueft Ablauf, gemeldeten Modus und die direkte Benutzer- beziehungsweise Einrichtungsgruppenbindung, bevor Passwort oder vorhandenes TOTP akzeptiert werden.

## Freigegebene Link-Ziele

```http
GET /v1/link-targets?capability=open_talk
Authorization: Bearer <authentik-user-access-token>
```

Die Bridge prueft das Token live am Authentik-UserInfo-Endpunkt und liefert nur fest konfigurierte Talk-Ziele. Sie gibt keine Authentik-Geraeteliste aus.

## FCM-Installation zuordnen

```http
PUT /v1/push/registrations/{authentik-device-uuid}
Authorization: Bearer <authentik-user-access-token>
Content-Type: application/json

{
  "provider": "fcm",
  "installation_id": "...",
  "mode": "personal",
  "notification_privacy": "standard",
  "app_version": "0.6.0",
  "authentik_device_token": "...",
  "key_id": "...",
  "public_key_jwk": {
    "kty": "EC",
    "crv": "P-256",
    "kid": "...",
    "x": "...",
    "y": "..."
  }
}
```

Vor dem Speichern prueft die Bridge das Benutzer-Access-Token ueber Authentik UserInfo und das Device Token live ueber den Statusendpunkt des Geräteportals. Dieser liest den Datensatz einschließlich Ablauf- und Deaktivierungsstatus direkt aus Authentik. Die von Authentik gelieferte Device-UUID muss mit der URL uebereinstimmen.

Die FCM-Installations-ID und das Authentik-Device-Token werden getrennt mit AES-256-GCM verschluesselt gespeichert. Der P-256-Schluessel dient nur der Kommunikationssignatur; sein privater Teil verlaesst den Android Keystore nie. `standard` liefert Titel und Zusammenfassung, `detailed` zusaetzlich eine kurze Vorschau, `minimal` nur einen neutralen lokalen Hinweis. Shared Tablets werden server- und clientseitig immer auf `minimal` reduziert.

## Authentik-Anmeldung in der App bestaetigen

Ein persoenliches Geraet registriert denselben Authentik-geprueften Kommunikationsschluessel unabhaengig von FCM:

```http
PUT /v1/auth/registrations/{authentik-device-uuid}
Authorization: Bearer <authentik-user-access-token>
Content-Type: application/json

{
  "mode": "personal",
  "app_version": "0.9.0",
  "authentik_device_token": "...",
  "key_id": "...",
  "public_key_jwk": {"kty":"EC","crv":"P-256","kid":"...","x":"...","y":"..."}
}
```

Die App fragt im Vordergrund alle zwei Sekunden signiert ab:

```http
GET /v1/auth/requests/pending
X-ML-Device-ID: ...
X-ML-Key-ID: ...
X-ML-Timestamp: ...
X-ML-Nonce: ...
X-ML-Signature: ...
```

Eine Antwort wird mit derselben kanonischen P-256-Signatur und dem unveraenderten JSON-Body an `POST /v1/auth/requests/{request_id}/decision` gesendet. Eine Anfrage kann nur einmal und vor Ablauf beantwortet werden. Nur persoenliche Registrierungen desselben stabilen Authentik-Subjects sehen sie.

Authentik ruft parallel seine normale Duo-Stufe gegen `/auth/v2/auth` auf. Der Bridge-Endpunkt akzeptiert ausschließlich Duo-Sig-V5 mit dem gemeinsamen HMAC-Geheimnis. `approve` wird als `allow` abgebildet; Ablehnung, Zeitablauf, unbekannter Benutzer, gesperrtes Authentik-Gerät und jeder interne Fehler werden ausdrücklich als `deny` zurückgegeben. Die Android-App selbst verwendet einen getrennten Authentik-Flow ohne diese Stufe, damit keine zirkuläre Anmeldung entstehen kann.

Bei Abmeldung oder Profil-Reset:

```http
DELETE /v1/push/registrations/{authentik-device-uuid}
Authorization: Bearer <authentik-user-access-token>
```

## Ereignis von Zimbra oder Nextcloud

```http
POST /internal/v1/events
X-ML-Source: zimbra
X-ML-Timestamp: 1789682400
X-ML-Signature: ...
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

Die Quellanfrage ist ueber Zeitstempel, Body-Hash und HMAC signiert. Ereignisse werden anhand `source + source_event_id` dedupliziert. Fuer Termine kann `deliver_at` gesetzt werden; die Bridge versendet erst zu diesem Zeitpunkt.

Vor jeder Zustellung prueft die Bridge das gespeicherte Device Token live bei Authentik. Lehnt Authentik es dauerhaft ab, wird nur die Kommunikationszuordnung aus der Bridge entfernt.

FCM enthaelt ausschliesslich:

```json
{
  "action": "fetch_notification",
  "event_id": "opaque-id",
  "event_type": "open_mail",
  "revision": "1"
}
```

Fuer eine Authentik-Anmeldebestaetigung enthaelt FCM ausschliesslich:

```json
{
  "action": "fetch_login_approval",
  "request_id": "opaque-id"
}
```

Die App ruft den Inhalt anschliessend signiert ab. Eine Zustimmung oder Ablehnung wird nie als FCM-Aktion angeboten.

## Signierter Detailabruf

```http
GET /v1/notifications/{event_id}
X-ML-Device-ID: <authentik-device-uuid>
X-ML-Key-ID: ...
X-ML-Timestamp: 1789682400
X-ML-Nonce: ...
X-ML-Signature: ...
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

Die Bridge prueft zusaetzlich:

- Zeitabweichung maximal 120 Sekunden,
- Nonce noch nie verwendet,
- oeffentlicher Kommunikationsschluessel und `key_id` passen,
- Authentik akzeptiert das Device Token live und liefert dieselbe Device-UUID,
- Benutzer ist in der Kommunikationszuordnung aktiv,
- Ereignis gehoert zur Benutzer-Geraete-Zuordnung,
- Datenschutzstufe erlaubt Details,
- Ereignis ist noch nicht abgelaufen.

Der Sperrbildschirm erhaelt immer eine neutrale oeffentliche Version. Die Detailantwort wird mit `Cache-Control: no-store` ausgeliefert.

## Talk oeffnen

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

Nur feste Zielgeraete, `open_talk`, ein syntaktisch gueltiger Raumtoken und maximal 30 Sekunden TTL werden akzeptiert. Eine vollstaendige URL, ein Benutzerkennwort, ein Shell-Befehl oder ein beliebiges URI-Schema ist nicht zulaessig.
