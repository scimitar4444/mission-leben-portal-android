# Authentik-Konfiguration

Diese App setzt den bestehenden zentralen Authentik-Flow fort und baut keinen zweiten, widersprüchlichen Anmeldeweg auf.

Ausgangslage aus der zuletzt dokumentierten Mission-Leben-Prüfung:

- Authentik 2026.8.2
- `default-authentication-flow` und `nextcloud-akademie-kerberos-sso` verwenden die Stufe `MFA verpflichtend`
- Ziel extern: Benutzername → Passwort → MFA
- Ziel intern: SPNEGO; falls das nicht greift, Benutzername → MFA
- bei Benutzerkonten ohne MFA soll ausschließlich `default-authenticator-totp-setup` angeboten werden
- WebAuthn/Passkeys bleiben als bereits eingerichtete Anmeldeklasse zulässig und können erst nach dem TOTP-Erstsetup ergänzt werden
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
| Authorization Flow | der getestete zentrale Browser-Flow mit `MFA verpflichtend` |
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

### Passkeys im Webcontainer

TOTP bleibt bei einem neuen Konto der erste Einrichtungsweg. In der Authenticator-Validation-Stufe bleibt die Aktion für „nicht konfiguriert“ auf `Configure`; unter `configuration_stages` steht nur `default-authenticator-totp-setup`. WebAuthn bleibt in `device_classes`, damit bereits später ergänzte Passkeys weiterhin validiert werden können. Diese Flow-Änderung muss zuerst mit externem Passwort+MFA und internem SPNEGO-Fallback getestet werden; die frühere Prüfung dokumentierte sie als Vorschlag, nicht als bereits produktiv angewendete Änderung.

Damit später ergänzte Passkeys im WebView funktionieren, muss `https://id.mission-leben.de/.well-known/assetlinks.json` das Paket `de.missionleben.portal` und den SHA-256-Fingerabdruck des endgültigen Release-Signierschlüssels enthalten. Ein Debug-Schlüssel darf nicht als Produktionsvertrauen eingetragen werden.

Die App aktiviert die native WebAuthn-/Credential-Manager-Unterstützung, sobald der installierte Android-System-WebView-Anbieter diese Funktion bereitstellt. Ohne diese Funktion bleibt die Anmeldung mit Passwort und TOTP möglich.

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

und öffnet zusätzlich den Provider-spezifischen End-Session-Endpunkt im geschützten Webcontainer. Anschließend werden dessen Cookies, Webspeicher, HTTP-Zugangsdaten, Cache und geschützte Downloads entfernt. Auf Shared Tablets ist die Schaltfläche „Sitzung sicher beenden“ bewusst besonders sichtbar; vor jeder neuen Shared-Anmeldung erfolgt zusätzlich eine Löschung.

Die Anmeldung selbst läuft ebenfalls in diesem Container. Dadurch teilen Authentik, Zimbra, Nextcloud/Talk und Vaultwarden eine kontrollierbare Browsersitzung. Der Client verwendet weiterhin Authorization Code mit PKCE; das OIDC-Token wird nicht in Webseiten injiziert.

## 4. WebView-Betrieb

Der Browsermotor ist Android System WebView und wird nicht in die APK eingebettet. Für verwaltete Geräte muss die Geräteverwaltung automatische Play-System-/WebView-Updates erzwingen und Geräte ohne aktiven WebView-Anbieter sperren. Die App verweigert den Start des Containers, wenn Android keinen Anbieter meldet.

Top-Level-Navigationen innerhalb der App werden auf HTTPS und die Build-Einstellung `ML_WEB_ALLOWED_HOST_SUFFIXES` beschränkt. Standard ist `mission-leben.de`; weitere intern kontrollierte Domain-Endungen werden kommasepariert ergänzt. Fremde HTTPS-, `mailto:`- und `tel:`-Links öffnen außerhalb des Containers.

## 5. Endpoint Devices

Authentik Endpoint Devices ist in 2026.8 weiterhin Early Preview und der offizielle Agent unterstützt Linux, macOS und Windows, nicht Android. Das Projekt spricht deshalb nicht unautorisiert interne Agent-Protokolle nach.

Der nun unter `bridge/` implementierte Pilot-Device-Service übernimmt:

1. einmaliges Enrollment mit kurzlebigem Token,
2. Verifikation des Android-Keystore-Schlüssels,
3. einen eigenen Status `pending`, `trusted` oder `blocked`,
4. die Bindung persönlicher Geräte an das stabile Authentik-Subject,
5. Sperren bei Geräteverlust oder Benutzer-Offboarding,
6. Ausgabe der freigegebenen Zielgeräte für den Talk-Handoff,
7. signierte Detailabrufe für Mail-, Termin- und Talk-Hinweise.

Der Vertrag ist in `DEVICE_SERVICE_API.md` festgelegt. Er nutzt keine undokumentierten Authentik-Agent-Protokolle und schreibt im Pilot nicht direkt in Endpoint Devices. Sobald Authentik einen stabilen Android-Agenten oder eine dokumentierte Integrations-API veröffentlicht, kann diese Schicht ausgetauscht oder gespiegelt werden, ohne OIDC oder UI neu zu bauen.

## 6. Policy-Grundsätze

- TOTP bleibt der erste MFA-Einrichtungsweg. Die Pilot-Gerätefreigabe ersetzt MFA nicht.
- Shared-Gerät: Es wird niemals ein Benutzer dauerhaft gebunden und Benachrichtigungen bleiben diskret.
- Persönliches Gerät: Benutzerbindung und Gerätebindung müssen beide aktiv sein.
- `user.is_active == false` muss Token-Erneuerung, App-Zugriff und persönliche Gerätebindung sperren.
- Gerät verloren: Gerätebindung sperren und zugehörige Refresh Tokens widerrufen.
- Admin-Anwendungen dürfen unabhängig vom Gerät weiterhin zusätzliche MFA verlangen.
- Geräteklassen und Trust-Status gehören in Attribute/Policies, nicht als Wildwuchs in die `ORG_*`-Struktur.
