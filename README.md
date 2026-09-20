# Mission Leben Zentral für Android

Offene Android-App für den sicheren Einstieg in die von Authentik freigegebenen Web-Anwendungen. Die App wird auf dem Gerät als **Mission Leben Zentral** angezeigt und unterstützt persönliche Mitarbeitergeräte sowie gemeinsam genutzte Tablets ab Android 13.

> Status: Pilot. Authentik Endpoint Devices 2026.8.3, OIDC/PKCE, der gehärtete Webcontainer und die getrennte Kommunikations-Bridge sind implementiert. Zimbra-, Nextcloud-/Talk- und FCM-Zustellung benötigen noch ihre produktiven Zugangsdaten und Ende-zu-Ende-Tests.

## Funktionen

- Anmeldung über Authentik mit OAuth 2.0 Authorization Code + PKCE
- die erforderliche Authentik-Endpoint-Prüfung läuft nach der Benutzererkennung und vor der Kennwort-/TOTP-Prüfung; Gerät und Benutzer beziehungsweise Einrichtungsgruppe müssen in Authentik zusammenpassen
- persönliche Geräte sind genau einem Benutzer zugeordnet; Shared Tablets hängen an der vorhandenen `ORG_*`-Einrichtungsgruppe ihres Standorts
- Telefonsprache oder direkte App-Auswahl für Deutsch, Englisch, Türkisch, Hindi, Spanisch, Französisch, Polnisch, Rumänisch und Ukrainisch; Deutsch bleibt die Rückfallsprache
- sichtbare Web-Apps werden live aus `/api/v3/core/applications/` geladen und zusätzlich auf die zentrale Authentik-Anwendungsgruppe `Mobil erreichbar` begrenzt
- Zimbra, Exchange OWA, Talk-Chat und jede weitere mobil freigegebene Webanwendung laufen in einem gehärteten In-App-Webcontainer mit gemeinsamer Authentik-Sitzung; die eigenständige Nextcloud-App und Warden sind mobil ausgeblendet
- vor jedem Start einer freigegebenen Webanwendung wird der OIDC-Token geprüft; führt eine abgelaufene Web-Sitzung zurück zur interaktiven Authentik-Anmeldung, schließt die App den Webcontainer, löscht nur die Benutzersitzung und fordert zur sicheren Neuanmeldung auf. Die Gerätefreigabe bleibt erhalten
- der Browsermotor wird über Android System WebView unabhängig von der APK aktualisiert
- Hell-/Dunkelmodus folgen dem Android-System; WebView meldet denselben Modus über `prefers-color-scheme` an Zimbra und andere Webanwendungen und darf Seiten ohne eigenes Dunkelthema bei Bedarf algorithmisch abdunkeln
- nur konfigurierte HTTPS-Domains dürfen im Container laden; fremde Links wechseln in den Systembrowser
- Talk läuft im Webcontainer bewusst als reiner Chat; Kamera- und Mikrofonanforderungen werden dort unabhängig von den Android-Berechtigungen abgewiesen. Die App blendet die Nextcloud-Kopfzeile aus und öffnet beim nächsten Start direkt den zuletzt verwendeten Raum; Abmelden/Zurücksetzen löscht diese lokale Erinnerung.
- Cookies, Webspeicher, HTTP-Zugangsdaten, Cache und geschützte Downloads werden bei sicherer Abmeldung oder Profilwechsel gelöscht
- persönliche Geräte: Refresh Token wird mit einem zufälligen Datenschlüssel verschlüsselt; nur Biometrie oder Gerätecode kann diesen Schlüssel über Android Keystore freigeben
- persönliche Geräte können Authentik-Anmeldungen im entsperrten Portal mit „Bestätigen“ oder „Ablehnen“ beantworten; ein sekundengenauer Countdown zeigt die verbleibende Gültigkeit der einzelnen Anfrage. Es gibt dabei keine zweite Biometrieabfrage. Die geöffnete App prüft alle zwei Sekunden, optionales FCM weckt sie später nur mit einer zufälligen Anfrage-ID. Authentik bleibt Benutzer- und Gerätequelle, Shared Tablets sind ausgeschlossen
- persönliche Geräte müssen sich nach exakt 90 Tagen erneut bestätigen: Die App übernimmt den gespeicherten Benutzernamen, Authentik prüft zuerst Gerät und Benutzerbindung und fordert ein bereits vorhandenes TOTP an, ansonsten das Passwort. Eine TOTP-Einrichtung wird nie erzwungen
- eine kompakte Anzeige nennt auf persönlichen Geräten die verbleibenden Tage der 90-Tage-Anmeldung
- die tägliche Startseite zeigt nur Sitzungsstatus und freigegebene Anwendungen; Sprache, Benachrichtigungen, technische Gerätedaten und Updates liegen gesammelt unter **Einstellungen**
- automatische OTA-Prüfung einmal je App-Start sowie eine manuelle Schaltfläche **Nach Updates suchen**; Updates kommen als öffentliches GitHub-Release und werden vor der Android-Installation anhand von Paketname, Version, Dateigröße, SHA-256 und App-Signatur geprüft
- Shared Tablets: kein Refresh Token und keine persistente Mitarbeitersitzung
- Authentik-Enrollment per verifiziertem HTTPS-App-Link mit einmaligem Token im URL-Fragment; eine vorhandene App öffnet direkt, andernfalls führt dieselbe Seite durch Installation und Einrichtung
- integrierter QR-Scanner für Enrollment-Links; ein Scan startet die Authentik-Geräteregistrierung ohne Abtippen
- eigener zustandsloser Geräte-Einrichtungscontainer für IT, Leitungen in der Zentrale, EL und PDL; Rollen und `ORG_*`-Bereiche werden serverseitig in Authentik geprüft, eine GF-Sonderrolle existiert nicht
- persönliche Selbstregistrierung für aktive Mitarbeiter mit bereits vorhandenem TOTP oder Passkey: Anmeldung im geschützten App-Browser, direkte Bindung an das eigene Authentik-Konto und automatischer Rücksprung zur App; keine Mitarbeitersuche und keine Faktor-Einrichtung
- neue Geräte wählen ihren persönlichen oder Shared-Modus ausschließlich aus dem zehn Minuten gültigen QR-Code; der Container entwertet den Authentik-Enrollment-Token nach dem ersten erfolgreichen Einlösen
- verschlüsselte Speicherung des Authentik-Device-Tokens unter einem Android-Keystore-Schlüssel
- native Antwort auf die Authentik Endpoint-Stage-Challenge im WebView; der Device Token wird nie an JavaScript ausgegeben
- Authentik Device Access Groups und deren Benutzer-/Gruppenbindungen steuern die Gerätefreigabe
- nicht exportierbare P-256-Kommunikationsidentität im Android Keystore für signierte Benachrichtigungsabrufe
- Nextcloud-Talk-Handoff an freigegebene Konferenzgeräte; übertragen wird ausschließlich der Raumtoken. Oberfläche und Bridge verlangen dafür die kanonische Authentik-Berechtigung `ENT_TALK_RAUMUEBERGABE`
- **Geräteprofil wechseln** erscheint ausschließlich mit der Authentik-Berechtigung `ENT_DEVICE_PROFILE_SWITCH`; Pilotmitgliedschaften werden nur im produktiven Authentik gepflegt und nicht im öffentlichen Quellcode veröffentlicht
- optionale FCM-Hinweise für Mail, Termine, Talk und Gerätesicherheit ab Android 13
- FCM transportiert nur eine Ereignis-ID; Details holt ein freigegebenes Gerät signiert von der eigenen Bridge
- Datenschutzstufen `Diskret`, `Standard` und `Ausführlich`; Shared Tablets erzwingen neutrale Hinweise
- Sperrbildschirm zeigt unabhängig von der Stufe keine Absender, Betreffzeilen, Termin- oder Talk-Inhalte
- separater Bridge-Container nur für Kommunikation: Authentik-UserInfo-Prüfung, verschlüsselte FCM-/Device-Token, Ereignisse und terminierte Zustellung; keine eigene Gerätedatenbank
- Zimbra-Worker mit SOAP WaitSet, begrenzter Mail-/Kalendersuche und Zuordnung zur stabilen Authentik-Benutzer-ID
- ein Hinweis kann nur eine bekannte Authentik-App öffnen, niemals eine vom Pushdienst gelieferte URL
- keine Client-Secrets im APK
- keine Passwörter oder TOTP-Secrets in der App

## Sicherheitsmodell

| Persönliches Gerät | Shared Tablet |
|---|---|
| Benutzer dauerhaft zugeordnet | kein fester Benutzer |
| `offline_access` angefordert | kein `offline_access` |
| Sitzung verschlüsselt gespeichert | Sitzung nur im Arbeitsspeicher |
| Biometrie oder Gerätecode öffnet lokalen Tresor; eine danach sichtbare Anmeldeanfrage benötigt keine zweite Biometrieabfrage | Benutzer meldet sich jedes Mal über Authentik an; keine App-Bestätigung |
| feste 90-Tage-Laufzeit mit Tagesanzeige; danach Geräteprüfung und vorhandenes TOTP, ansonsten Passwort | Zugriff nur für Mitglieder der zugeordneten `ORG_*`-Einrichtungsgruppe; vor jeder neuen Anmeldung werden Webdaten entfernt |

Biometrie gibt auf persönlichen Geräten ausschließlich die bereits aufgebaute lokale Sitzung frei. Bei der Erstanmeldung sind Benutzername und Passwort erforderlich; der geprüfte Authentik-Endpoint bildet den Gerätefaktor. Bei der planmäßigen Wiederanmeldung nach 90 Tagen verwendet Authentik ein bereits eingerichtetes TOTP, andernfalls erneut das Passwort. Die App richtet TOTP nicht ein. Auf gemeinsam genutzten Tablets wird keine persönliche Sitzung dauerhaft gespeichert; dort müssen sowohl der Gerätenachweis als auch die Einrichtungsgruppen-Mitgliedschaft und das persönliche Passwort stimmen.

## Bauen

Voraussetzungen:

- JDK 17 oder neuer
- Android SDK Platform 36
- Android Build Tools 35+

```bash
./gradlew test assembleDebug
```

Die Debug-APK liegt anschließend unter `app/build/outputs/apk/debug/`.

Die Bridge wird separat geprüft:

```bash
PYTHONPATH=bridge/src python -m unittest discover -s bridge/tests -v
```

Containerstart und Servergrenzen stehen in [bridge/README.md](bridge/README.md).

## Konfiguration

Nicht geheime Build-Parameter können als Gradle-Properties gesetzt werden:

```properties
ML_AUTHENTIK_BASE_URL=https://id.mission-leben.de
ML_OIDC_ISSUER=https://id.mission-leben.de/application/o/mission-leben-portal/
ML_OIDC_CLIENT_ID=mission-leben-android
ML_DEVICE_SERVICE_BASE_URL=https://id.mission-leben.de/device-bridge
ML_WEB_ALLOWED_HOST_SUFFIXES=mission-leben.de,akademie-mission-leben.de
ML_AUTHENTIK_AUTHENTICATION_FLOW_SLUGS=mission-leben-android-authentication,mission-leben-browser-authentication,mission-leben-zimbra-authentication,default-authentication-flow,nextcloud-akademie-kerberos-sso
ML_FIREBASE_APPLICATION_ID=1:1234567890:android:…
ML_FIREBASE_API_KEY=AIza…
ML_FIREBASE_PROJECT_ID=mission-leben-portal
ML_FIREBASE_SENDER_ID=1234567890
```

Die Geräteregistrierung verwendet immer `ML_AUTHENTIK_BASE_URL` und spricht Authentik Endpoint Devices direkt an. `ML_DEVICE_SERVICE_BASE_URL` betrifft ausschließlich Push, Benachrichtigungsdetails und Talk-Handoff; der Mission-Leben-Pilotwert ist bereits voreingestellt.

`ML_WEB_ALLOWED_HOST_SUFFIXES` ist eine kommaseparierte Liste kontrollierter Domain-Endungen. Standardmäßig dürfen ausschließlich `mission-leben.de`, `akademie-mission-leben.de` und deren Subdomains im In-App-Webcontainer laufen. SaaS- oder Fremdlinks öffnen außerhalb des Containers.

`ML_AUTHENTIK_AUTHENTICATION_FLOW_SLUGS` enthält ausschließlich die Authentik-Flows, die eine erneute Benutzeranmeldung darstellen. So erkennt die App eine abgelaufene Web-Sitzung, ohne normale Provider-Freigabe-Flows fälschlich abzubrechen.

FCM bleibt vollständig deaktiviert, solange einer der vier `ML_FIREBASE_*`-Werte fehlt. Diese Firebase-App-Kennung ist Client-Konfiguration, kein Servergeheimnis. Das Firebase-Dienstkonto für den Versand darf dagegen niemals in Gradle-Properties, APK oder Git-Repository liegen. Die Einrichtung ist in [docs/FCM_SETUP.md](docs/FCM_SETUP.md) beschrieben.

Der Enrollment-Scanner verwendet den Google Code Scanner. Die Erkennung läuft auf dem Gerät; beim ersten Aufruf kann Google Play Services das Scanner-Modul `barcode_ui` nachladen. Bei neuen Geräten akzeptiert die App ausschließlich einen vollständigen, vom Geräte-Einrichtungscontainer erzeugten Deep Link mit Token-ID und Gerätemodus. Ein alter Token-only-Link funktioniert nur noch auf einem Gerät, dessen Modus bereits lokal feststeht. Beliebige URLs und rohe QR-Texte werden verworfen.

`ML_DEVICE_SERVICE_BASE_URL` zeigt im Pilot auf den eigenen Kommunikationscontainer. Er läuft separat hinter TLS und enthält keine Authentik-Gerätefreigaben.

Die Authentik-Seite ist in [docs/AUTHENTIK_SETUP.md](docs/AUTHENTIK_SETUP.md) beschrieben.
Build, Signierung und Veröffentlichung der OTA-Releases sind in [docs/OTA_UPDATES.md](docs/OTA_UPDATES.md) beschrieben. Der private Signierschlüssel bleibt außerhalb von GitHub.

## Projektstruktur

```text
app/
  auth/       OIDC/PKCE und Token-Lebenszyklus
  data/       Authentik-Portal-API und lokale Einstellungen
  device/     Authentik-Enrollment, Device Token und sicherer Kommunikationskanal
  security/   Android Keystore, Geräteidentität, Biometrie-Tresor
  ui/         Jetpack-Compose-Oberfläche
  web/        gehärteter WebView-Container und Domainregeln
bridge/
  src/        Ereignis-, FCM-, Talk- und Zimbra-Kommunikations-Bridge
  tests/      Signatur-, Datenschutz- und Versandtests
  Dockerfile  nicht privilegierter Container
docs/
  AUTHENTIK_SETUP.md
  DEVICE_SERVICE_API.md
  FCM_SETUP.md
  NOTIFICATIONS.md
  OTA_UPDATES.md
  THREAT_MODEL.md
```

## Open Source

Apache License 2.0. Beiträge sind willkommen; siehe [CONTRIBUTING.md](CONTRIBUTING.md) und [SECURITY.md](SECURITY.md).
