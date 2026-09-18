# Mission Leben Zentral für Android

Offene Android-App für den sicheren Einstieg in die von Authentik freigegebenen Web-Anwendungen. Die App wird auf dem Gerät als **Mission Leben Zentral** angezeigt und unterstützt persönliche Mitarbeitergeräte sowie gemeinsam genutzte Tablets ab Android 13.

> Status: Pilot. Authentik Endpoint Devices 2026.8.3, OIDC/PKCE, der gehärtete Webcontainer und die getrennte Kommunikations-Bridge sind implementiert. Zimbra-, Nextcloud-/Talk- und FCM-Zustellung benötigen noch ihre produktiven Zugangsdaten und Ende-zu-Ende-Tests.

## Funktionen

- Anmeldung über Authentik mit OAuth 2.0 Authorization Code + PKCE
- die erforderliche Authentik-Endpoint-Prüfung läuft in der App nach dem Passwort und vor MFA
- ein serverseitig als `shared` freigegebenes und vom Gerät ebenfalls als `shared` gemeldetes Tablet ersetzt TOTP; persönliche und nicht registrierte Geräte durchlaufen weiter TOTP
- Telefonsprache oder direkte App-Auswahl für Deutsch, Englisch, Türkisch, Hindi, Spanisch, Französisch, Polnisch, Rumänisch und Ukrainisch; Deutsch bleibt die Rückfallsprache
- sichtbare Web-Apps werden live aus `/api/v3/core/applications/` geladen und zusätzlich auf die zentrale Authentik-Anwendungsgruppe `Mobil erreichbar` begrenzt
- Zimbra, Nextcloud und Talk-Chat laufen in einem gehärteten In-App-Webcontainer mit gemeinsamer Authentik-Sitzung; Warden bleibt bis zur externen Freigabe mobil ausgeblendet
- der Browsermotor wird über Android System WebView unabhängig von der APK aktualisiert
- nur konfigurierte HTTPS-Domains dürfen im Container laden; fremde Links wechseln in den Systembrowser
- Talk läuft im Webcontainer bewusst als reiner Chat; Kamera- und Mikrofonanforderungen werden dort unabhängig von den Android-Berechtigungen abgewiesen. Die App blendet die Nextcloud-Kopfzeile aus und öffnet beim nächsten Start direkt den zuletzt verwendeten Raum; Abmelden/Zurücksetzen löscht diese lokale Erinnerung.
- Cookies, Webspeicher, HTTP-Zugangsdaten, Cache und geschützte Downloads werden bei sicherer Abmeldung oder Profilwechsel gelöscht
- persönliche Geräte: Refresh Token wird mit einem zufälligen Datenschlüssel verschlüsselt; nur Biometrie oder Gerätecode kann diesen Schlüssel über Android Keystore freigeben
- Shared Tablets: kein Refresh Token und keine persistente Mitarbeitersitzung
- Authentik-Enrollment per Code oder Deep Link `de.missionleben.portal://enroll?token=…`; Authentik erzeugt Device, Connection, Device Token und Fakten-Snapshots
- integrierter QR-Scanner für Enrollment-Links; ein Scan startet die Authentik-Geräteregistrierung ohne Abtippen
- verschlüsselte Speicherung des Authentik-Device-Tokens unter einem Android-Keystore-Schlüssel
- native Antwort auf die Authentik Endpoint-Stage-Challenge im WebView; der Device Token wird nie an JavaScript ausgegeben
- Authentik Device Access Groups und deren Benutzer-/Gruppenbindungen steuern die Gerätefreigabe
- nicht exportierbare P-256-Kommunikationsidentität im Android Keystore für signierte Benachrichtigungsabrufe
- Nextcloud-Talk-Handoff an freigegebene Konferenzgeräte; übertragen wird ausschließlich der Raumtoken
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
| Biometrie oder Gerätecode öffnet lokalen Tresor | Benutzer meldet sich jedes Mal über Authentik an |
| zentrale Kontosperre beendet weiteren Zugriff; lokale Webdaten werden bei sicherer Abmeldung/Profilwechsel entfernt | Gerätefreigabe kann separat gesperrt werden; vor jeder neuen Anmeldung werden Webdaten entfernt |

Biometrie ersetzt nicht das Authentik-Passwort. Sie gibt auf persönlichen Geräten ausschließlich eine bereits aufgebaute lokale Sitzung frei. Auf gemeinsam genutzten Tablets wird keine persönliche Sitzung dauerhaft gespeichert; dort bildet der kryptografische Authentik-Gerätenachweis neben dem Passwort den zweiten Faktor.

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
ML_WEB_ALLOWED_HOST_SUFFIXES=mission-leben.de
ML_FIREBASE_APPLICATION_ID=1:1234567890:android:…
ML_FIREBASE_API_KEY=AIza…
ML_FIREBASE_PROJECT_ID=mission-leben-portal
ML_FIREBASE_SENDER_ID=1234567890
```

Die Geräteregistrierung verwendet immer `ML_AUTHENTIK_BASE_URL` und spricht Authentik Endpoint Devices direkt an. `ML_DEVICE_SERVICE_BASE_URL` betrifft ausschließlich Push, Benachrichtigungsdetails und Talk-Handoff; der Mission-Leben-Pilotwert ist bereits voreingestellt.

`ML_WEB_ALLOWED_HOST_SUFFIXES` ist eine kommaseparierte Liste kontrollierter Domain-Endungen. Standardmäßig dürfen ausschließlich `mission-leben.de` und dessen Subdomains im In-App-Webcontainer laufen. SaaS- oder Fremdlinks öffnen außerhalb des Containers.

FCM bleibt vollständig deaktiviert, solange einer der vier `ML_FIREBASE_*`-Werte fehlt. Diese Firebase-App-Kennung ist Client-Konfiguration, kein Servergeheimnis. Das Firebase-Dienstkonto für den Versand darf dagegen niemals in Gradle-Properties, APK oder Git-Repository liegen. Die Einrichtung ist in [docs/FCM_SETUP.md](docs/FCM_SETUP.md) beschrieben.

Der Enrollment-Scanner verwendet den Google Code Scanner. Die Erkennung läuft auf dem Gerät; beim ersten Aufruf kann Google Play Services das Scanner-Modul `barcode_ui` nachladen. Akzeptiert wird ausschließlich `de.missionleben.portal://enroll?token=...`, nicht eine beliebige URL oder ein roher QR-Text.

`ML_DEVICE_SERVICE_BASE_URL` zeigt im Pilot auf den eigenen Kommunikationscontainer. Er läuft separat hinter TLS und enthält keine Authentik-Gerätefreigaben.

Die Authentik-Seite ist in [docs/AUTHENTIK_SETUP.md](docs/AUTHENTIK_SETUP.md) beschrieben.

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
  THREAT_MODEL.md
```

## Open Source

Apache License 2.0. Beiträge sind willkommen; siehe [CONTRIBUTING.md](CONTRIBUTING.md) und [SECURITY.md](SECURITY.md).
