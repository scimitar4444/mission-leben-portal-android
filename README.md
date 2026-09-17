# Mission Leben Portal für Android

Offene Android-App für den sicheren Einstieg in die von Authentik freigegebenen Web-Anwendungen. Die App unterstützt persönliche Mitarbeitergeräte und gemeinsam genutzte Tablets ab Android 13.

> Status: frühes, baubares MVP. Die Authentik-OIDC-Anbindung ist implementiert. Für die Registrierung in Authentik Endpoint Devices und den Geräte-Link-Kanal wird der in `docs/DEVICE_SERVICE_API.md` beschriebene kleine Serverdienst benötigt.

## Funktionen

- Anmeldung über Authentik mit OAuth 2.0 Authorization Code + PKCE
- TOTP-zuerst-Flow aus der bestehenden Authentik-Konfiguration wird unverändert verwendet
- sichtbare Web-Apps werden live aus `/api/v3/core/applications/` geladen
- Zimbra, Nextcloud, Talk und Vaultwarden laufen in einem gehärteten In-App-Webcontainer mit gemeinsamer Authentik-Sitzung
- der Browsermotor wird über Android System WebView unabhängig von der APK aktualisiert
- nur konfigurierte HTTPS-Domains dürfen im Container laden; fremde Links wechseln in den Systembrowser
- Talk erhält Kamera und Mikrofon nur nach Android-Freigabe und nur auf erlaubten Domains
- Cookies, Webspeicher, HTTP-Zugangsdaten, Cache und geschützte Downloads werden bei sicherer Abmeldung oder Profilwechsel gelöscht
- persönliche Geräte: Refresh Token wird mit einem zufälligen Datenschlüssel verschlüsselt; nur Biometrie oder Gerätecode kann diesen Schlüssel über Android Keystore freigeben
- Shared Tablets: kein Refresh Token und keine persistente Mitarbeitersitzung
- nicht exportierbare P-256-Geräteidentität im Android Keystore
- Enrollment per Code oder Deep Link `de.missionleben.portal://enroll?token=…`
- vorbereitet für Authentik Endpoint Devices über einen schmalen Device Service
- Nextcloud-Talk-Handoff an freigegebene Konferenzgeräte; übertragen wird ausschließlich der Raumtoken
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

Biometrie ersetzt nicht das Authentik-Passwort. Sie gibt ausschließlich eine bereits durch Passwort und TOTP aufgebaute lokale Sitzung frei.

## Bauen

Voraussetzungen:

- JDK 17 oder neuer
- Android SDK Platform 36
- Android Build Tools 35+

```bash
./gradlew test assembleDebug
```

Die Debug-APK liegt anschließend unter `app/build/outputs/apk/debug/`.

## Konfiguration

Nicht geheime Build-Parameter können als Gradle-Properties gesetzt werden:

```properties
ML_AUTHENTIK_BASE_URL=https://id.mission-leben.de
ML_OIDC_ISSUER=https://id.mission-leben.de/application/o/mission-leben-portal/
ML_OIDC_CLIENT_ID=mission-leben-android
ML_DEVICE_SERVICE_BASE_URL=https://device.mission-leben.de
ML_WEB_ALLOWED_HOST_SUFFIXES=mission-leben.de
```

`ML_DEVICE_SERVICE_BASE_URL` bleibt standardmäßig leer. Dann funktionieren Authentik-Anmeldung und App-Portal, die Geräteregistrierung und Talk-Übergabe werden aber als noch nicht konfiguriert angezeigt.

`ML_WEB_ALLOWED_HOST_SUFFIXES` ist eine kommaseparierte Liste kontrollierter Domain-Endungen. Standardmäßig dürfen ausschließlich `mission-leben.de` und dessen Subdomains im In-App-Webcontainer laufen. SaaS- oder Fremdlinks öffnen außerhalb des Containers.

Die Authentik-Seite ist in [docs/AUTHENTIK_SETUP.md](docs/AUTHENTIK_SETUP.md) beschrieben.

## Projektstruktur

```text
app/
  auth/       OIDC/PKCE und Token-Lebenszyklus
  data/       Authentik-Portal-API und lokale Einstellungen
  device/     Enrollment und sicherer Geräte-Link-Kanal
  security/   Android Keystore, Geräteidentität, Biometrie-Tresor
  ui/         Jetpack-Compose-Oberfläche
  web/        gehärteter WebView-Container und Domainregeln
docs/
  AUTHENTIK_SETUP.md
  DEVICE_SERVICE_API.md
  NOTIFICATIONS.md
  THREAT_MODEL.md
```

## Open Source

Apache License 2.0. Beiträge sind willkommen; siehe [CONTRIBUTING.md](CONTRIBUTING.md) und [SECURITY.md](SECURITY.md).
