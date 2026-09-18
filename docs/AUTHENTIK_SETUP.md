# Authentik-Konfiguration

Diese App setzt den bestehenden zentralen Authentik-Flow fort und baut keinen zweiten, widersprüchlichen Anmeldeweg auf.

Ausgangslage aus der zuletzt dokumentierten Mission-Leben-Prüfung:

- Authentik 2026.8.3
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
| Anwendung | Mission Leben Zentral Android |
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

Im Pilot ist sowohl die OIDC-Anwendung als auch die Android Device Access Group nur an `authentik Admins` gebunden. Für den Rollout werden dort die vorgesehenen Mitarbeitergruppen ergänzt; zusätzliche App-Zugriffe bleiben weiterhin über die bestehenden `APP_*`-Policies geregelt.

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

Zusätzlich zeigt der Android-Client ausschließlich Anwendungen mit der Authentik-Anwendungsgruppe `Mobil erreichbar`. Diese Kennzeichnung beschreibt nur die technische Erreichbarkeit aus dem mobilen Netz; Benutzer- und Gruppen-Policies bleiben unverändert die eigentliche Zugriffsentscheidung. Im Pilot sind `zimbra-mail`, `exchange-owa` und `talk` markiert. Die eigenständige Nextcloud-App und Warden bleiben unmarkiert und damit nur im normalen Portal sichtbar.

Da Nextcloud Talk Android-WebViews für Audio- und Videoanrufe nicht als vollständig unterstützten Browser behandelt, betreibt der Client Talk bewusst nur als eingebetteten Chat. Auf den vertrauenswürdigen Talk-Seiten werden WebRTC-Anforderungen für Kamera und Mikrofon immer abgewiesen und der Benutzer erhält einen klaren Hinweis. Die installierte Talk-App wird nicht automatisch gestartet; Kontodaten oder WebView-Cookies werden nicht an eine Fremd-App übertragen.

## 3. Abmeldung

Die App widerruft Access- und Refresh Token über:

```text
/application/o/revoke/
```

und öffnet zusätzlich den Provider-spezifischen End-Session-Endpunkt im geschützten Webcontainer. Anschließend werden dessen Cookies, Webspeicher, HTTP-Zugangsdaten, Cache und geschützte Downloads entfernt. Auf Shared Tablets ist die Schaltfläche „Sitzung sicher beenden“ bewusst besonders sichtbar; vor jeder neuen Shared-Anmeldung erfolgt zusätzlich eine Löschung.

Die Anmeldung selbst läuft ebenfalls in diesem Container. Dadurch teilen Authentik, Zimbra, Nextcloud/Talk und Warden eine kontrollierbare Browsersitzung. Der Client verwendet weiterhin Authorization Code mit PKCE; das OIDC-Token wird nicht in Webseiten injiziert.

Der WebView-User-Agent enthält zusätzlich `MissionLebenMode/personal` oder `MissionLebenMode/shared`. Nur im persönlichen Modus erhält die Authentik-Browsersitzung eine feste Dauer von 30 Tagen, damit die biometrisch entsperrte App Zimbra und andere SSO-Anwendungen nach einem Prozessneustart ohne zweite Anmeldung öffnen kann. Diese Stufe ist sowohl an `mission-leben-browser-authentication` (Portal und Nextcloud; damit auch Talk und die Nextcloud-App Warden) als auch an den eigenen Zimbra-Fluss `mission-leben-zimbra-authentication` gebunden. Der OAuth-Refresh-Token bleibt davon getrennt im biometrisch geschützten Android-Tresor. Shared-Geräte verwenden weiterhin ausschließlich eine Browser-Session ohne persistentes Cookie; außerdem löscht die App dort die Webdaten vor jeder Anmeldung und bei sicherer Abmeldung.

## 4. WebView-Betrieb

Der Browsermotor ist Android System WebView und wird nicht in die APK eingebettet. Für verwaltete Geräte muss die Geräteverwaltung automatische Play-System-/WebView-Updates erzwingen und Geräte ohne aktiven WebView-Anbieter sperren. Die App verweigert den Start des Containers, wenn Android keinen Anbieter meldet.

Top-Level-Navigationen innerhalb der App werden auf HTTPS und die Build-Einstellung `ML_WEB_ALLOWED_HOST_SUFFIXES` beschränkt. Standard sind `mission-leben.de` und `akademie-mission-leben.de`; weitere intern kontrollierte Domain-Endungen werden kommasepariert ergänzt. Fremde HTTPS-, `mailto:`- und `tel:`-Links öffnen außerhalb des Containers.

## 5. Endpoint Devices

Authentik Endpoint Devices ist in 2026.8 Early Preview. Für diesen ausdrücklich so freigegebenen Pilot ist Authentik trotzdem die alleinige Gerätedatenbank.

Das idempotente Skript `authentik/bootstrap_endpoint_devices.py` legt an:

1. den Agent Connector `Mission Leben Android` mit eigenem Challenge-Schlüssel,
2. die Device Access Group `Mission Leben Android - Pilot`,
3. den Public-OIDC-Client `mission-leben-android`,
4. eine erforderliche Endpoint Stage nach dem Passwort und vor den TOTP-Stufen,
5. eine unmittelbar nachgelagerte Zugriffsprüfung der Device Access Group,
6. bedingte Policies, sodass diese beiden Stufen ausschließlich für den User-Agent `MissionLebenPortal/*` laufen,
7. eine zusätzliche Policy auf beiden TOTP-Stufen: Nur ein kryptografisch nachgewiesenes Gerät, dessen Authentik-Access-Group serverseitig `shared` erlaubt und dessen App denselben Modus meldet, überspringt TOTP.
8. eine auf zwölf Stunden begrenzte Browser-SSO-Stufe ausschließlich für `MissionLebenMode/personal`; der normale Login bleibt für Shared-Geräte und andere Browser flüchtig.

Die App sendet Enrollment direkt an `/api/v3/endpoints/agents/connectors/enroll/`, liest ihre Authentik-Geräte-ID aus `agent_config`, meldet Android-Fakten über `check_in` und beantwortet die Endpoint-Stage-Challenge mit dem im Android Keystore verschlüsselten Device Token. Authentik speichert Device, Connection, Token, Fakten, Ablauf und Access Group. Der separate Container besitzt keine Tabellen für Devices oder Enrollment-Tokens.

Vor jeder Anmeldung auf einem Shared Tablet löscht der Webcontainer Cookies, Webspeicher, Cache, Formulardaten und Downloads. Deshalb setzt die OIDC-Anfrage dort bewusst kein `prompt=login`: Nach dem gerade abgeschlossenen Authentik-Flow würde dieser Parameter erneut in denselben Identifikationsschritt führen. Die lokale Bereinigung verhindert trotzdem, dass die Sitzung des vorherigen Mitarbeiters übernommen wird.

Ein Pilot-Enrollment-Token wird mit `authentik/create_pilot_enrollment_token.py` erzeugt, 24 Stunden gültig und der Pilot-Access-Group zugeordnet. Nach dem geplanten Enrollment wird er in Authentik ablaufen gelassen oder gelöscht. Das Skript darf nur mit in eine root-only Datei umgeleiteter Ausgabe ausgeführt werden.

Für die App wird daraus lokal ein QR-Code mit dem Deep Link `de.missionleben.portal://enroll?token=...` erzeugt. `authentik/generate_enrollment_qr.py` liest den Token ausschließlich über stdin, schreibt die PNG-Datei mit Modus `0600` und gibt den Token nicht aus. Der QR-Code ist wie der Enrollment-Token selbst ein Geheimnis und darf weder in Git noch in Tickets oder öffentliche Dateifreigaben gelangen.

Gerät sperren: Unter **Endpoint Devices → Devices** das Gerät ablaufen lassen oder löschen. Dadurch lehnt `agent_config` das Device Token ab; die App löscht Sitzung und Webdaten, und die Kommunikations-Bridge verwirft die Push-Zuordnung bei ihrer nächsten Live-Prüfung.

## 6. Policy-Grundsätze

- TOTP bleibt für persönliche, unbekannte und nicht als `shared` freigegebene Geräte der MFA-Weg. Auf einem freigegebenen Shared Tablet ersetzt dessen kryptografischer Authentik-Gerätenachweis TOTP; die Anmeldung benötigt weiterhin das Benutzerpasswort.
- Shared-Gerät: Es wird niemals eine Mitarbeitersitzung dauerhaft gespeichert und Benachrichtigungen bleiben diskret.
- Persönliches Gerät: Authentik-Device, Device Access Group, Benutzerbindung und Benutzerkonto müssen aktiv sein.
- `user.is_active == false` muss Token-Erneuerung, App-Zugriff und persönliche Gerätebindung sperren.
- Gerät verloren: Authentik-Device ablaufen lassen oder löschen und zugehörige Refresh Tokens widerrufen.
- Admin-Anwendungen dürfen unabhängig vom Gerät weiterhin zusätzliche MFA verlangen.
- Geräteklassen und Trust-Status gehören in Attribute/Policies, nicht als Wildwuchs in die `ORG_*`-Struktur.
