# Authentik-Konfiguration

Diese App verwendet einen eng begrenzten eigenen Authentik-Anmeldeflow. Er nutzt die vorhandene Passwortquelle, bereits eingerichtete TOTP-Geräte, Endpoint-Geräteprüfung und zentrale Anwendungs-Policies. Er erzwingt keine TOTP-Einrichtung und verändert weder die internen/externen Zweige noch die SPNEGO-/MFA-Regeln des zentralen Browser-Flows.

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
| Authorization Flow | `mission-leben-android-authorization` |
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

Der kleine Scope `ml_features` liefert ausschließlich die Capabilities `open_talk` und `device_profile_switch`. Sie werden rekursiv aus den kanonischen Berechtigungsgruppen `ENT_TALK_RAUMUEBERGABE` und `ENT_DEVICE_PROFILE_SWITCH` abgeleitet. Die Bridge prüft `open_talk` nochmals serverseitig. `Mobil erreichbar`, normale Talk-/Nextcloud-Gruppen, Organisationsgruppen und Gerätebesitz erteilen diese Funktionsrechte ausdrücklich nicht. Pilotmitgliedschaften werden ausschließlich im produktiven Authentik verwaltet und nicht im öffentlichen Repository dokumentiert; für `ENT_TALK_RAUMUEBERGABE` werden keine Mitglieder automatisch erraten.

Die OIDC-Anwendung besitzt keine Administrator- oder Mitarbeitergruppenbindung mehr. Stattdessen verweigert der Android-Anmeldeflow jeden unbekannten, abgelaufenen, als deaktiviert markierten oder falsch gebundenen Endpoint. Ein persönliches Device besitzt eine direkte `DeviceUserBinding` zu genau einem Benutzer; ein Shared Tablet liegt in einer eigenen Device Access Group, die an genau die vorhandene `ORG_*`-Einrichtungsgruppe gebunden ist. Die in der App angezeigten Fachanwendungen bleiben weiterhin durch ihre bestehenden `APP_*`-Policies eingeschränkt.

Der Public Client verwendet zusätzlich einen eigenen Authorization Flow. Dieser fordert auch bei einer bereits vorhandenen Authentik-Browsersitzung erneut die signierte Endpoint-Challenge an und prüft Gerätestatus, Geräteablauf, Modus sowie Benutzer- beziehungsweise Einrichtungsbindung. Ein bekanntes `client_id` und eine bestehende Authentik-Sitzung reichen deshalb nicht aus, um ein OIDC-Token für die Android-App zu erhalten.

### Vorhandenes TOTP und Passkeys

Der Android-Flow bietet niemals eine TOTP-Registrierung an. Die Erstanmeldung verwendet Benutzername, Passwort und den gebundenen Endpoint. Erst bei der planmäßigen Wiederanmeldung nach 90 Tagen prüft eine bedingte Authenticator-Validation-Stufe ein bereits bestätigtes TOTP. Besitzt der Benutzer kein TOTP, bleibt die Passwortstufe aktiv. Die zentralen Browser-Flows dürfen unabhängig davon weiterhin ihre bisherige MFA-Registrierung und Passkeys verlangen.

Damit später ergänzte Passkeys im WebView funktionieren, muss `https://id.mission-leben.de/.well-known/assetlinks.json` das Paket `de.missionleben.portal` und den SHA-256-Fingerabdruck des endgültigen Release-Signierschlüssels enthalten. Ein Debug-Schlüssel darf nicht als Produktionsvertrauen eingetragen werden.

Die App aktiviert die native WebAuthn-/Credential-Manager-Unterstützung, sobald der installierte Android-System-WebView-Anbieter diese Funktion bereitstellt. Der Android-spezifische Erstlogin bleibt davon unabhängig mit Benutzername, Passwort und gebundenem Gerät möglich.

## 2. App-Liste

Die App ruft auf:

```http
GET /api/v3/core/applications/?only_with_launch_url=true&page_size=100&ordering=name
Authorization: Bearer <access-token>
```

Authentik führt bei diesem List-Endpunkt die Policy-Prüfung für den aktuellen Benutzer aus. Dadurch sieht die App dieselben freigegebenen Anwendungen wie das Authentik Application Dashboard.

Zusätzlich zeigt der Android-Client ausschließlich Anwendungen mit der Authentik-Anwendungsgruppe `Mobil erreichbar`. Diese Kennzeichnung beschreibt nur die technische Erreichbarkeit aus dem mobilen Netz; Benutzer- und Gruppen-Policies bleiben unverändert die eigentliche Zugriffsentscheidung. Im Pilot sind `zimbra-mail`, `exchange-owa` und `talk` markiert. Die eigenständige Nextcloud-App und Warden bleiben unmarkiert und damit nur im normalen Portal sichtbar.

Die Authentik-Anwendung `talk` startet über den bereits in Nextcloud eingerichteten zentralen `user_oidc`-Anbieter und gibt `/apps/spreed/` als Rücksprungziel mit. Dadurch entfällt auf der Nextcloud-Anmeldeseite der zusätzliche Klick auf „Mission Leben“; es entsteht weder ein zweiter Benutzerbestand noch eine parallele Authentifizierung. Nach dem OIDC-Rücksprung stellt der Android-WebView weiterhin den zuletzt verwendeten Talk-Raum aus seinem lokalen Speicher wieder her.

Da Nextcloud Talk Android-WebViews für Audio- und Videoanrufe nicht als vollständig unterstützten Browser behandelt, betreibt der Client Talk bewusst nur als eingebetteten Chat. Der gesamte WebView verweigert Kamera- und Mikrofonanforderungen; die Kamera-Berechtigung der App ist ausschließlich für den nativen QR-Scanner bestimmt. Auf Talk-Seiten erhält der Benutzer zusätzlich einen klaren Hinweis. Die installierte Talk-App wird nicht automatisch gestartet; Kontodaten oder WebView-Cookies werden nicht an eine Fremd-App übertragen.

## 3. Abmeldung

Die App widerruft Access- und Refresh Token über:

```text
/application/o/revoke/
```

und öffnet zusätzlich den Provider-spezifischen End-Session-Endpunkt im geschützten Webcontainer. Anschließend werden dessen Cookies, Webspeicher, HTTP-Zugangsdaten, Cache und geschützte Downloads entfernt. Auf Shared Tablets ist die Schaltfläche „Sitzung sicher beenden“ bewusst besonders sichtbar; vor jeder neuen Shared-Anmeldung erfolgt zusätzlich eine Löschung.

Die Anmeldung selbst läuft ebenfalls in diesem Container. Dadurch teilen Authentik, Zimbra, Nextcloud/Talk und Warden eine kontrollierbare Browsersitzung. Der Client verwendet weiterhin Authorization Code mit PKCE; das OIDC-Token wird nicht in Webseiten injiziert.

Der WebView-User-Agent enthält zusätzlich `MissionLebenMode/personal` oder `MissionLebenMode/shared`. Nur im persönlichen Modus erhalten die Authentik-Browsersitzung und der geschützte OAuth-Refresh-Token eine Laufzeit von 90 Tagen. Die App erzwingt zusätzlich selbst die absolute Grenze `auth_time + 90 Tage`; sie verlässt sich dafür nicht auf die bei Authentik standardmäßig gleitende Refresh-Token-Rotation. Die verbleibende Laufzeit wird in der App tageweise angezeigt. Dadurch öffnen Zimbra und andere SSO-Anwendungen nach einem Prozessneustart weiterhin ohne zweite Anmeldung, die Frist verlängert sich aber nicht unbemerkt bei jeder Nutzung. Die Browserstufe ist sowohl an den eigenen App-Flow `mission-leben-android-authentication` als auch an den Zimbra-Fluss `mission-leben-zimbra-authentication` gebunden. Der OAuth-Refresh-Token bleibt davon getrennt im biometrisch geschützten Android-Tresor. Shared-Geräte verwenden weiterhin ausschließlich eine Browser-Session ohne persistentes Cookie; außerdem löscht die App dort die Webdaten vor jeder Anmeldung und bei sicherer Abmeldung.

Nach Ablauf der 90 Tage verwirft die App Token, Tresor und Webdaten, behält aber auf einem weiterhin freigegebenen persönlichen Gerät genau den normalisierten OIDC-Anmeldenamen als `login_hint`. Die neue OIDC-Anfrage enthält `prompt=login`. Authentiks Identification Stage übernimmt den Hinweis ohne sichtbare Benutzernamenseite; anschließend muss die Endpoint Stage Gerät, persönlichen Modus, direkte Benutzerbindung, Deaktivierungsstatus und Ablaufstatus prüfen. Hat der gebundene Benutzer bereits ein bestätigtes TOTP, wird das Passwort übersprungen und nur dieses TOTP abgefragt. Ohne TOTP läuft stattdessen die Passwortstufe. Bei fehlender Identität, gelöschter App, Profilwechsel, Shared-Modus, unbekanntem, deaktiviertem, abgelaufenem oder falsch zugeordnetem Gerät wird die Anmeldung verweigert beziehungsweise auf den vollständigen sicheren Ablauf zurückgeführt.

## 4. WebView-Betrieb

Der Browsermotor ist Android System WebView und wird nicht in die APK eingebettet. Für verwaltete Geräte muss die Geräteverwaltung automatische Play-System-/WebView-Updates erzwingen und Geräte ohne aktiven WebView-Anbieter sperren. Die App verweigert den Start des Containers, wenn Android keinen Anbieter meldet.

Top-Level-Navigationen innerhalb der App werden auf HTTPS und die Build-Einstellung `ML_WEB_ALLOWED_HOST_SUFFIXES` beschränkt. Standard sind `mission-leben.de` und `akademie-mission-leben.de`; weitere intern kontrollierte Domain-Endungen werden kommasepariert ergänzt. Fremde HTTPS-, `mailto:`- und `tel:`-Links öffnen außerhalb des Containers.

## 5. Endpoint Devices

Authentik Endpoint Devices ist in 2026.8 Early Preview. Für diesen ausdrücklich so freigegebenen Pilot ist Authentik trotzdem die alleinige Gerätedatenbank.

Für wiederholbare Ende-zu-Ende-Tests kann zusätzlich die parallel installierbare Debug-App `de.missionleben.portal.debug` verwendet werden. Sie besitzt eine eigene Android-Keystore- und Endpoint-Identität und verwendet ausschließlich den zweiten, exakt eingetragenen Redirect `de.missionleben.portal.debug:/oauth2redirect`. `authentik/bootstrap_e2e_debug.py` legt dafür nur das reservierte interne Testkonto `ml-portal-e2e`, die isolierte Organisation `ORG_E2E_TEST`, ein bestätigtes Test-TOTP und die bestehende Rolle `ML_DEVICE_INIT_EL` an. Passwort und TOTP-Schlüssel kommen aus root-only Dateien und werden weder im Repository noch in der Skriptausgabe gespeichert. Das Konto erhält keine Anwendungsgruppen und damit keinen Zugriff auf echte Zimbra-, Nextcloud- oder Talk-Daten.

Das idempotente Skript `authentik/bootstrap_endpoint_devices.py` legt an. Für den OIDC-Provider verwendet es bewusst den eigenen Flow `mission-leben-android-authentication`; der zentrale Browser-Flow mit seinen internen/externen Netz- und SPNEGO-Policies bleibt unverändert:

Eine optionale Pilotzuordnung für `ENT_DEVICE_PROFILE_SWITCH` wird nur zur Laufzeit über `ML_DEVICE_PROFILE_SWITCH_PILOT_USERNAME` übergeben. Ohne diese Variable verändert das öffentliche Bootstrap-Skript die bestehende Mitgliedschaft dieser Gruppe nicht. Reale Benutzernamen gehören weder in das Repository noch in Befehlsbeispiele oder Release-Notizen.

1. den Agent Connector `Mission Leben Android` mit eigenem Challenge-Schlüssel,
2. die bindungsfreie Device Access Group `Mission Leben Android - Personal` für persönliche Geräte sowie standortbezogene Gruppen `Mission Leben Android - Shared - ORG_*`,
3. den Public-OIDC-Client `mission-leben-android`,
4. eine erforderliche Endpoint Stage nach der Identifikation und vor dem Passwort,
5. einen eigenen Authorization Flow, der die Endpoint-Challenge selbst bei vorhandener Authentik-Sitzung erneut ausführt,
6. eine unmittelbar nachgelagerte, fehlertoleranzfreie Prüfung von gemeldetem Gerätemodus und Authentik-Benutzer-/Einrichtungsbindung,
7. bedingte Policies, sodass diese beiden Stufen im Authentication Flow ausschließlich für den User-Agent `MissionLebenPortal/*` laufen; im eigenen Authorization Flow ist die Geräteprüfung dagegen zwingend,
8. eine TOTP-Stufe mit `last_auth_threshold=seconds=0`, die ausschließlich bei der 90-Tage-Wiederanmeldung eines korrekt gebundenen persönlichen Benutzers mit bereits bestätigtem TOTP läuft und niemals eine TOTP-Einrichtung anbietet,
9. eine auf 90 Tage begrenzte Browser-SSO-Stufe ausschließlich für `MissionLebenMode/personal`; der normale Login bleibt für Shared-Geräte und andere Browser flüchtig,
10. einen 90 Tage gültigen Refresh Token mit praktisch deaktivierter Rotation (`refresh_token_threshold=seconds=1`) sowie die zusätzliche absolute 90-Tage-Prüfung im Android-Client,
11. eine eng begrenzte Wiederanmeldung, bei der `login_hint` den bekannten Benutzer übernimmt und Authentik vorhandenes TOTP verwendet, andernfalls das Passwort.

Die App löst neue, vom Geräte-Einrichtungsportal erzeugte QR-Codes einmalig über dessen Redeem-Endpunkt ein. Der zustandslose Container prüft den kurzlebigen Authentik-Enrollment-Token und ruft anschließend `/api/v3/endpoints/agents/connectors/enroll/` auf. Die App liest ihre Authentik-Geräte-ID aus `agent_config`, meldet Android-Fakten über `check_in` und beantwortet die Endpoint-Stage-Challenge mit dem im Android Keystore verschlüsselten Device Token. Authentik speichert Device, Connection, Token, Fakten, Ablauf, Device Access Group und Benutzer- beziehungsweise Einrichtungsbindung. Der separate Container besitzt dafür keine eigene Datenbank.

Vor jeder Anmeldung auf einem Shared Tablet löscht der Webcontainer Cookies, Webspeicher, Cache, Formulardaten und Downloads. Deshalb setzt die OIDC-Anfrage dort bewusst kein `prompt=login`: Nach dem gerade abgeschlossenen Authentik-Flow würde dieser Parameter erneut in denselben Identifikationsschritt führen. Die lokale Bereinigung verhindert trotzdem, dass die Sitzung des vorherigen Mitarbeiters übernommen wird.

Der reguläre Einrichtungsweg ist der separate Container in `enrollment-portal/`. Aktive Mitarbeiter mit vorhandenem TOTP oder Passkey dürfen dort ausschließlich ihr eigenes persönliches Gerät registrieren. IT, Leitungen in der Zentrale, EL und PDL erhalten zusätzlich die Verwaltungsansicht. Der Container grenzt EL, PDL und zentrale Leitungen auf ihre vorhandenen `ORG_*`-Gruppen ein; nur IT besitzt globalen Suchzugriff. Für persönliche Geräte wird die direkte Benutzerbindung vor Ausgabe des Einmal-Links oder QR-Codes angelegt, für Shared Tablets die Bindung an genau eine Einrichtung.

Für persönliche Benutzerbindungen erzwingt das Portal genau einen aktiven Endpoint. Ein vorhandenes Handy wird in der Oberfläche angezeigt, bleibt bis zum erfolgreichen Enrollment des neuen Geräts aktiv und erhält erst danach den dauerhaften Attributstatus `disabled` samt Zeitpunkt und Grund. Es wird nicht gelöscht und erhält bewusst kein sofortiges Ablaufdatum, weil Authentik abgelaufene Endpoint-Datensätze bereinigen kann. Dieselbe Gerätekennung kann sich erneut registrieren, ohne sich dabei selbst zu sperren. Shared Tablets bleiben mehrgerätefähig und werden von dieser Logik nicht verändert.

Die App-Version 0.8.1 öffnet für die Selbstregistrierung `/self` im geschützten WebView und löscht vorher alle alten Webdaten. Der anwendungsbezogene Authorization Flow des Proxy-Providers akzeptiert ausschließlich ein bereits eingerichtetes TOTP oder einen Passkey (`not_configured_action=deny`). Nach der Bestätigung leitet der Container einen persönlichen Zehn-Minuten-Link zurück zur App; diese registriert den Endpoint und startet anschließend den normalen OIDC-Flow in derselben Authentik-Sitzung.

Der angezeigte QR-Code ist zehn Minuten gültig und enthält Token, Token-UUID und den festgelegten Modus im Fragment eines verifizierten HTTPS-App-Links. Das Fragment wird nicht an den Webserver übertragen. Ist die App installiert, öffnet Android sie direkt; andernfalls führt die öffentliche Installationsseite durch Download und Übergabe an die App. Nach erfolgreichem Enrollment löscht der Container den Authentik-Enrollment-Token, bevor er den Device Token an die App zurückgibt. Der QR-Code darf trotzdem weder fotografiert noch in Tickets oder Dateifreigaben abgelegt werden.

Die alten Skripte `create_enrollment_token.py`, `assign_device_access.py` und `generate_enrollment_qr.py` sind nur noch dokumentierter Notfall-/Migrationsbestand und kein Einrichtungsweg für neue Geräte. `create_pilot_enrollment_token.py` bleibt absichtlich deaktiviert. Eine frische App ab Version 0.8.0 verlangt den vollständigen Portal-QR; ein alter Token-only-Link wird nur noch akzeptiert, wenn auf dem Gerät bereits ein Modus gespeichert ist.

Gerät sperren: Unter **Endpoint Devices → Devices** in den Attributen `mission-leben.de/status=disabled` setzen und Zeitpunkt sowie Grund dokumentieren. Die Android-Policies lehnen dieses Device anschließend serverseitig ab; die App löscht Sitzung und Webdaten, und die Kommunikations-Bridge verwirft die Push-Zuordnung bei ihrer nächsten Live-Prüfung. Löschen oder ein sofortiges Ablaufdatum sind für die normale Sperrung nicht zulässig, weil die Nachverfolgbarkeit erhalten bleiben muss.

Mitarbeiter-Offboarding: Das Mitarbeiterkonto wird in Authentik deaktiviert. `authentik/reconcile_inactive_personal_devices.py` wird durch `mission-leben-device-offboarding.timer` alle fünf Minuten ausgeführt. Es verarbeitet ausschließlich direkte Benutzerbindungen an Device Access Groups mit `mission-leben.de/purpose=android-portal` und `mission-leben.de/mode=personal`, deaktiviert deren Geräte, entzieht Authentik-Sitzungen sowie Core-/OAuth-Tokens und übergibt das stabile Subject an die getrennte Bridge-Bereinigung. Der Nextcloud-OIDC-Provider verwendet zusätzlich `user_oidc`-Backchannel-Logout. Shared-Geräte werden nicht pauschal gesperrt, wenn ein einzelnes Mitglied der Einrichtungsgruppe ausscheidet. Zimbra verwendet derzeit SAML; das Beenden einer bereits bestehenden Zimbra-Mailboxsitzung benötigt noch einen eigenen, sicher authentifizierten Zimbra-Administrationsadapter.

## 6. App-Bestätigung als Authentik-Faktor

Die App-Bestätigung verwendet die in Authentik Community vorhandene Duo-Stufe, aber keinen Duo-Cloud-Dienst und keine Enterprise-Funktion. Die Stufe spricht die signierte Duo Auth API gegen den separaten Kommunikationscontainer. Authentik bleibt für Benutzer, `DuoDevice`, MFA-Auswahl und Endpoint Devices zuständig; die Bridge speichert nur kurzlebige Anmeldeanfragen und die bereits für Kommunikation benötigte Gerätezuordnung.

Nach PostgreSQL- und Bridge-Backup werden einmalige, zufällige Werte erzeugt und sowohl in der Root-only Bridge-`.env` als auch beim Bootstrap als `ML_APP_APPROVAL_INTEGRATION_KEY`, `ML_APP_APPROVAL_SECRET_KEY` und `ML_APP_APPROVAL_API_HOSTNAME=id.mission-leben.de` verwendet. `authentik/bootstrap_app_approval.py`:

- legt `Mission Leben Zentral - App-Bestätigung` als Duo-Stufe an,
- ergänzt `duo` in der vorhandenen Stufe `MFA verpflichtend`, ohne TOTP oder WebAuthn zu entfernen,
- verweigert die Einrichtung, falls die zentrale MFA-Stufe versehentlich im Android-Anmeldeflow liegt,
- prüft, dass der Android-OIDC-Provider `hashed_user_id` verwendet,
- verknüpft bestehende aktive persönliche Endpoint-Bindungen über `user.uid`,
- überspringt gruppengebundene Shared Tablets.

Die ausgegebene `duo_stage_uuid` wird im Geräte-Einrichtungscontainer als `ML_ENROLL_APP_APPROVAL_STAGE_UUID` gesetzt. Neue persönliche Registrierungen erhalten damit automatisch den Faktor. Der Android-OIDC-Flow bleibt absichtlich ohne diese Stufe: Die App muss sich zuerst selbst öffnen können, bevor sie andere Anmeldungen bestätigt.

Solange FCM noch nicht konfiguriert ist, sieht die geöffnete App eine Anfrage spätestens nach etwa zwei Sekunden. Der Dialog zählt anhand des serverseitigen Ablaufzeitpunkts sekundengenau herunter; bei Ablauf verschwinden die Entscheidungsschaltflächen mit der Anfrage. Ist die App bereits entsperrt, erscheint keine zweite Biometrieabfrage. Später kann FCM lediglich das sofortige Abrufen anstoßen; Anwendungsname oder andere Anmeldedaten werden nicht über FCM versendet.

Der von Authentik 2026.8.3 verwendete Duo-Python-Client besitzt eine eigene, gegenüber dem Betriebssystem verkleinerte CA-Liste. Das auf `id.mission-leben.de` eingesetzte Let's-Encrypt-Zertifikat wird deshalb ohne Ergänzung abgelehnt, obwohl die normale Authentik-HTTPS-Prüfung erfolgreich ist. Auf dem Authentik-Docker-Host erzeugt `authentik/prepare_duo_ca_bundle.sh` aus der mitgelieferten Duo-Liste plus `ISRG Root X1` und `ISRG Root X2` eine gezielte Erweiterung. `authentik/docker-compose.duo-ca.example.yml` wird als `docker-compose.override.yml` abgelegt; der vom Skript ausgegebene `DUO_CLIENT_CA_PATH` kommt in die Root-only `.env`. Server und Worker mounten die Datei ausschließlich lesbar. Die TLS-Prüfung darf nicht deaktiviert werden.

Nach jedem Authentik-Update muss das Skript vor dem Neustart erneut gegen den neuen Server-Container ausgeführt werden. Danach sind `docker compose config -q`, der Authentik-Readiness-Endpunkt und ein signiertes `stage.auth_client().ping()` zu prüfen. Ändert sich der Python-Pfad, wird ausschließlich `DUO_CLIENT_CA_PATH` auf den neu ausgegebenen Wert aktualisiert.

## 7. Policy-Grundsätze

- Die Erstanmeldung auf einem persönlichen Gerät benötigt Benutzername, Passwort und den korrekt zum Benutzer gebundenen Endpoint; eine TOTP-Registrierung wird nicht angeboten.
- Nach 90 Tagen verwendet ein persönlicher Benutzer sein bereits vorhandenes TOTP. Ohne bestätigtes TOTP fordert Authentik stattdessen das Passwort. Der bekannte Benutzername wird von der App übernommen.
- Shared Tablets benötigen bei jeder Mitarbeitersitzung Benutzername und Passwort. Zusätzlich müssen Endpoint-Modus und Mitgliedschaft in der dem Tablet zugeordneten `ORG_*`-Einrichtungsgruppe stimmen.
- Shared-Gerät: Es wird niemals eine Mitarbeitersitzung dauerhaft gespeichert und Benachrichtigungen bleiben diskret.
- Persönliches Gerät: Authentik-Device, Device Access Group, Benutzerbindung und Benutzerkonto müssen aktiv sein.
- `user.is_active == false` muss Token-Erneuerung, App-Zugriff und persönliche Gerätebindung sperren.
- Gerät verloren: Authentik-Device mit `mission-leben.de/status=disabled` sperren, Grund und Zeitpunkt dokumentieren und zugehörige Refresh Tokens widerrufen; nicht löschen.
- Admin-Anwendungen dürfen unabhängig vom Gerät weiterhin zusätzliche MFA verlangen.
- Geräteklassen und Trust-Status gehören in Attribute/Policies, nicht als Wildwuchs in die `ORG_*`-Struktur.
