# Authentik-Konfiguration

Diese App setzt den bestehenden zentralen Authentik-Flow fort und baut keinen zweiten, widersprüchlichen Anmeldeweg auf.

Geprüfte Ausgangslage aus der Mission-Leben-Umgebung:

- Authentik 2026.8.1
- zentrale Authentifizierung: `mission-leben-browser-authentication`
- bei Benutzerkonten ohne MFA wird ausschließlich `default-authenticator-totp-setup` angeboten
- WebAuthn/Passkeys können erst danach ergänzt werden
- Benutzerportal: `https://id.mission-leben.de/if/user/`
- Anwendungsberechtigungen werden über bestehende `APP_*`-Gruppen und Policies ermittelt
- Windows-Domänenclients behalten ihre direkten SPNEGO-Einstiege; die Android-App ersetzt diesen Weg nicht

## 1. OAuth2/OIDC-Provider

In Authentik eine neue Anwendung mit Provider anlegen:

| Einstellung | Wert |
|---|---|
| Anwendung | Mission Leben Portal Android |
| Slug | `mission-leben-portal` |
| Provider-Typ | OAuth2/OpenID Connect |
| Client-Typ | Public |
| Client-ID | `mission-leben-android` |
| Authorization Flow | `mission-leben-browser-authentication` |
| Redirect URI | exakt `de.missionleben.portal:/oauth2redirect` |
| Redirect-Matching | strict, keine Wildcards |
| Grant | Authorization Code |
| PKCE | S256/required |
| Signing Key | vorhandener asymmetrischer Signierschlüssel |

Kein Client-Secret in die App aufnehmen. Ein Public Client kann ein Secret nicht vertraulich halten.

Scopes:

- `openid`
- `profile`
- `email`
- `offline_access`
- `goauthentik.io/api`

`offline_access` wird von der App nur im persönlichen Gerätemodus angefordert. Shared Tablets fordern diesen Scope nicht an. `goauthentik.io/api` wird benötigt, um die für die angemeldete Person sichtbaren Anwendungen über die Authentik-API abzurufen.

Die Anwendung sollte dieselbe aktive-Benutzer-Policy verwenden wie das Portal. Zusätzliche App-Zugriffe werden nicht in der Android-App gepflegt; maßgeblich bleiben die bestehenden Authentik-Policies.

## 2. App-Liste

Die App ruft auf:

```http
GET /api/v3/core/applications/?only_with_launch_url=true&page_size=100&ordering=name
Authorization: Bearer <access-token>
```

Authentik führt bei diesem List-Endpunkt die Policy-Prüfung für den aktuellen Benutzer aus. Dadurch sieht die App dieselben freigegebenen Anwendungen wie das Authentik Application Dashboard.

## 3. Abmeldung

Die App widerruft Access- und Refresh Token über:

```text
/application/o/revoke/
```

und öffnet zusätzlich den Provider-spezifischen End-Session-Endpunkt im Systembrowser. Auf Shared Tablets ist die Schaltfläche „Sitzung sicher beenden“ bewusst besonders sichtbar.

## 4. Endpoint Devices

Authentik Endpoint Devices ist in 2026.8 weiterhin Early Preview und der offizielle Agent unterstützt Linux, macOS und Windows, nicht Android. Das Projekt spricht deshalb nicht unautorisiert interne Agent-Protokolle nach.

Der vorgesehene Device Service übernimmt:

1. einmaliges Enrollment mit kurzlebigem Token,
2. Verifikation des Android-Keystore-Schlüssels,
3. Anlegen beziehungsweise Abgleichen des Geräts in Authentik Endpoint Devices,
4. Zuordnung zu Device Access Groups und optional zum Benutzer,
5. Sperren bei Geräteverlust oder Benutzer-Offboarding,
6. Ausgabe der freigegebenen Zielgeräte für den Talk-Handoff.

Der Vertrag ist in `DEVICE_SERVICE_API.md` festgelegt. Sobald Authentik einen stabilen Android-Agenten veröffentlicht, kann diese Implementierung ausgetauscht werden, ohne OIDC oder UI neu zu bauen.

## 5. Policy-Grundsätze

- TOTP bleibt der Standard; nur ein explizit freigegebenes Gerät darf eine Ausnahme auslösen.
- Shared-Gerät: Device Trust kann den Gerätefaktor erfüllen, aber es wird niemals ein Benutzer dauerhaft gebunden.
- Persönliches Gerät: Benutzerbindung und Gerätebindung müssen beide aktiv sein.
- `user.is_active == false` muss Token-Erneuerung, App-Zugriff und persönliche Gerätebindung sperren.
- Gerät verloren: Gerätebindung sperren und zugehörige Refresh Tokens widerrufen.
- Admin-Anwendungen dürfen unabhängig vom Gerät weiterhin zusätzliche MFA verlangen.
- Geräteklassen und Trust-Status gehören in Attribute/Policies, nicht als Wildwuchs in die `ORG_*`-Struktur.
