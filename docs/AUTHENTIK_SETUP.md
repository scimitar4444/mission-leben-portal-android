# Authentik-Konfiguration

Diese App verwendet einen eng begrenzten eigenen Authentik-Anmeldeflow. Er nutzt die vorhandene Passwortquelle, TOTP-Einrichtung, Geräteprüfung und zentralen Anwendungs-Policies, verändert aber nicht die internen/externen Zweige oder SPNEGO-Regeln des zentralen Browser-Flows.

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
| Authentication Flow | `mission-leben-android-authentication` |
| Authorization Flow | `default-provider-authorization-implicit-consent` |
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

Der WebView-User-Agent enthält zusätzlich `MissionLebenMode/personal` oder `MissionLebenMode/shared`. Nur im persönlichen Modus erhalten die Authentik-Browsersitzung und der geschützte OAuth-Refresh-Token eine Laufzeit von 90 Tagen. Die App erzwingt zusätzlich selbst die absolute Grenze `auth_time + 90 Tage`; sie verlässt sich dafür nicht auf die bei Authentik standardmäßig gleitende Refresh-Token-Rotation. Dadurch öffnen Zimbra und andere SSO-Anwendungen nach einem Prozessneustart weiterhin ohne zweite Anmeldung, die Frist verlängert sich aber nicht unbemerkt bei jeder Nutzung. Die Browserstufe ist sowohl an den eigenen App-Flow `mission-leben-android-authentication` als auch an den Zimbra-Fluss `mission-leben-zimbra-authentication` gebunden. Der OAuth-Refresh-Token bleibt davon getrennt im biometrisch geschützten Android-Tresor. Shared-Geräte verwenden weiterhin ausschließlich eine Browser-Session ohne persistentes Cookie; außerdem löscht die App dort die Webdaten vor jeder Anmeldung und bei sicherer Abmeldung.

Nach Ablauf der 90 Tage verwirft die App Token, Tresor und Webdaten, behält aber auf einem weiterhin freigegebenen persönlichen Gerät genau den normalisierten OIDC-Anmeldenamen als `login_hint`. Die neue OIDC-Anfrage enthält `prompt=login`. Authentiks einfache Identification Stage übernimmt den Hinweis ohne sichtbare Benutzernamenseite, danach muss die Endpoint Stage das registrierte Gerät erneut kryptografisch prüfen. Nur wenn App, persönlicher Gerätemodus, Benutzerbindung, Ablaufstatus und Device-Policies stimmen, wird die Passwortstufe übersprungen; die vorhandene TOTP-Stufe bleibt zwingend. Bei fehlender Identität, gelöschter App, Profilwechsel, Shared-Modus, unbekanntem, abgelaufenem oder gesperrtem Gerät fällt der Ablauf auf die vollständige Anmeldung zurück.

## 4. WebView-Betrieb

Der Browsermotor ist Android System WebView und wird nicht in die APK eingebettet. Für verwaltete Geräte muss die Geräteverwaltung automatische Play-System-/WebView-Updates erzwingen und Geräte ohne aktiven WebView-Anbieter sperren. Die App verweigert den Start des Containers, wenn Android keinen Anbieter meldet.

Top-Level-Navigationen innerhalb der App werden auf HTTPS und die Build-Einstellung `ML_WEB_ALLOWED_HOST_SUFFIXES` beschränkt. Standard sind `mission-leben.de` und `akademie-mission-leben.de`; weitere intern kontrollierte Domain-Endungen werden kommasepariert ergänzt. Fremde HTTPS-, `mailto:`- und `tel:`-Links öffnen außerhalb des Containers.

## 5. Endpoint Devices

Authentik Endpoint Devices ist in 2026.8 Early Preview. Für diesen ausdrücklich so freigegebenen Pilot ist Authentik trotzdem die alleinige Gerätedatenbank.

Das idempotente Skript `authentik/bootstrap_endpoint_devices.py` legt an. Für den OIDC-Provider verwendet es bewusst den eigenen Flow `mission-leben-android-authentication`; der zentrale Browser-Flow mit seinen internen/externen Netz- und SPNEGO-Policies bleibt unverändert:

1. den Agent Connector `Mission Leben Android` mit eigenem Challenge-Schlüssel,
2. die Device Access Group `Mission Leben Android - Pilot`,
3. den Public-OIDC-Client `mission-leben-android`,
4. eine erforderliche Endpoint Stage nach der Identifikation und vor dem Passwort,
5. eine unmittelbar nachgelagerte Zugriffsprüfung der Device Access Group,
6. bedingte Policies, sodass diese beiden Stufen ausschließlich für den User-Agent `MissionLebenPortal/*` laufen,
7. eine eigene TOTP-Stufe nur für die Android-App mit `last_auth_threshold=seconds=0`: Nur ein kryptografisch nachgewiesenes Gerät, dessen Authentik-Access-Group serverseitig `shared` erlaubt und dessen App denselben Modus meldet, überspringt TOTP. Persönliche Geräte werden dadurch bei der 90-Tage-Wiederanmeldung immer nach dem Code gefragt,
8. eine auf 90 Tage begrenzte Browser-SSO-Stufe ausschließlich für `MissionLebenMode/personal`; der normale Login bleibt für Shared-Geräte und andere Browser flüchtig,
9. einen 90 Tage gültigen Refresh Token mit praktisch deaktivierter Rotation (`refresh_token_threshold=seconds=1`) sowie die zusätzliche absolute 90-Tage-Prüfung im Android-Client,
10. eine eng begrenzte Wiederanmeldung, bei der `login_hint` den bekannten Benutzer übernimmt und das Passwort nur nach erfolgreicher persönlicher Endpoint-Prüfung entfällt; TOTP bleibt erforderlich.

Die App sendet Enrollment direkt an `/api/v3/endpoints/agents/connectors/enroll/`, liest ihre Authentik-Geräte-ID aus `agent_config`, meldet Android-Fakten über `check_in` und beantwortet die Endpoint-Stage-Challenge mit dem im Android Keystore verschlüsselten Device Token. Authentik speichert Device, Connection, Token, Fakten, Ablauf und Access Group. Der separate Container besitzt keine Tabellen für Devices oder Enrollment-Tokens.

Vor jeder Anmeldung auf einem Shared Tablet löscht der Webcontainer Cookies, Webspeicher, Cache, Formulardaten und Downloads. Deshalb setzt die OIDC-Anfrage dort bewusst kein `prompt=login`: Nach dem gerade abgeschlossenen Authentik-Flow würde dieser Parameter erneut in denselben Identifikationsschritt führen. Die lokale Bereinigung verhindert trotzdem, dass die Sitzung des vorherigen Mitarbeiters übernommen wird.

Ein Pilot-Enrollment-Token wird mit `authentik/create_pilot_enrollment_token.py` erzeugt, 24 Stunden gültig und der Pilot-Access-Group zugeordnet. Nach dem geplanten Enrollment wird er in Authentik ablaufen gelassen oder gelöscht. Das Skript darf nur mit in eine root-only Datei umgeleiteter Ausgabe ausgeführt werden.

Für die App wird daraus lokal ein QR-Code mit dem Deep Link `de.missionleben.portal://enroll?token=...` erzeugt. `authentik/generate_enrollment_qr.py` liest den Token ausschließlich über stdin, schreibt die PNG-Datei mit Modus `0600` und gibt den Token nicht aus. Der QR-Code ist wie der Enrollment-Token selbst ein Geheimnis und darf weder in Git noch in Tickets oder öffentliche Dateifreigaben gelangen.

Gerät sperren: Unter **Endpoint Devices → Devices** das Gerät ablaufen lassen oder löschen. Dadurch lehnt `agent_config` das Device Token ab; die App löscht Sitzung und Webdaten, und die Kommunikations-Bridge verwirft die Push-Zuordnung bei ihrer nächsten Live-Prüfung.

## 6. Policy-Grundsätze

- TOTP bleibt für persönliche, unbekannte und nicht als `shared` freigegebene Geräte der MFA-Weg. Auf einem freigegebenen Shared Tablet ersetzt dessen kryptografischer Authentik-Gerätenachweis TOTP; die Anmeldung benötigt weiterhin das Benutzerpasswort. Auf persönlichen Geräten sind bei der ersten Anmeldung Benutzername, Passwort und TOTP erforderlich; erst nach 90 Tagen ersetzt ein erneut geprüfter Endpoint das Passwort, während TOTP weiterhin zwingend bleibt.
- Shared-Gerät: Es wird niemals eine Mitarbeitersitzung dauerhaft gespeichert und Benachrichtigungen bleiben diskret.
- Persönliches Gerät: Authentik-Device, Device Access Group, Benutzerbindung und Benutzerkonto müssen aktiv sein.
- `user.is_active == false` muss Token-Erneuerung, App-Zugriff und persönliche Gerätebindung sperren.
- Gerät verloren: Authentik-Device ablaufen lassen oder löschen und zugehörige Refresh Tokens widerrufen.
- Admin-Anwendungen dürfen unabhängig vom Gerät weiterhin zusätzliche MFA verlangen.
- Geräteklassen und Trust-Status gehören in Attribute/Policies, nicht als Wildwuchs in die `ORG_*`-Struktur.
