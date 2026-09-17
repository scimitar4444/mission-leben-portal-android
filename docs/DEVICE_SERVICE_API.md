# Device Service API v1

Der Device Service ist die schmale Brücke zwischen Android Keystore, Authentik Endpoint Devices und den domänenunabhängigen Mission-Leben-Companions. Authentik bleibt Quelle für Identität, Gerätebindung und Berechtigung; der Service ist nur Enrollment- und Transportkanal.

Alle produktiven Endpunkte verwenden HTTPS. Enrollment-Tokens sind einmalig, kurzlebig und im Klartext weder zu protokollieren noch zu speichern.

## Gerät registrieren

```http
POST /v1/enrollments
Content-Type: application/json

{
  "enrollment_token": "…",
  "mode": "personal | shared",
  "device_name": "Google Pixel Tablet",
  "platform": "android",
  "os_version": "13",
  "app_version": "0.1.0",
  "key_id": "…",
  "public_key_jwk": {
    "kty": "EC",
    "crv": "P-256",
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

Der private Schlüssel wird im Android Keystore erzeugt und verlässt das Gerät nie. In der nächsten Ausbaustufe liefert der Server zunächst eine Nonce; die App erzeugt den Schlüssel mit Android-Key-Attestation und signiert die Nonce. Für den MVP muss die Freigabe deshalb immer durch einen Admin erfolgen.

## Freigegebene Link-Ziele

```http
GET /v1/link-targets?capability=open_talk
Authorization: Bearer <authentik-user-access-token>
```

```json
{
  "results": [
    {
      "id": "vk-ffm-01",
      "name": "Besprechungsraum 1",
      "location": "Frankfurt",
      "online": true
    }
  ]
}
```

Der Server validiert das Authentik-Token und wertet die Geräte-/Benutzerbindungen aus. Die App darf keine vollständige globale Geräteliste erhalten.

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

Sicherheitsregeln:

- nur `action=open_talk` im ersten Release
- nur erlaubte Nextcloud-Talk-Token, keine vollständigen URLs
- TTL höchstens 30 Sekunden
- Zielgerät muss online, vertrauenswürdig und für den Benutzer freigegeben sein
- Companion baut die URL selbst aus einer fest konfigurierten Nextcloud-Basis und dem Token
- Companion zeigt je nach Gerätekonfiguration eine Bestätigung oder öffnet den Browser
- jede Übergabe wird mit Benutzer, Zielgerät, Ergebnis und Zeitstempel auditiert
- keine `file:`, benutzerdefinierten Schemes, Shell-Befehle oder beliebigen HTTPS-Ziele

Geplante Zimbra-Termine verwenden weiterhin die Raumressource als Quelle. Der App-Handoff ist vor allem für spontane Meetings gedacht; Konferenz-PCs können über einen temporären öffentlichen Talk als Gast mit festem Raumnamen beitreten.
