# Mission Leben Zentral für Android

Offene Android-App für den sicheren Einstieg in die von Authentik freigegebenen Web-Anwendungen. Die App wird auf dem Gerät als **Mission Leben Zentral** angezeigt und unterstützt persönliche Mitarbeitergeräte sowie gemeinsam genutzte Tablets ab Android 13.

> Status: frühes, baubares MVP. Authentik-OIDC, der gehärtete Webcontainer, der optionale FCM-Client und ein containerisierter Pilot der Device-/Notification-Bridge sind implementiert. Die reale Zimbra-, Nextcloud- und Authentik-Umgebung ist noch nicht produktiv angebunden oder Ende-zu-Ende getestet.

## Funktionen

- Anmeldung über Authentik mit OAuth 2.0 Authorization Code + PKCE
- TOTP bleibt der erste MFA-Einrichtungsweg; die dafür dokumentierte Authentik-Flow-Änderung muss vor Produktion noch angewendet und extern sowie mit internem SPNEGO-Fallback getestet werden
- Telefonsprache oder direkte App-Auswahl für Deutsch, Englisch, Türkisch, Hindi, Spanisch, Französisch, Polnisch, Rumänisch und Ukrainisch; Deutsch bleibt die Rückfallsprache
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
- signierte Statusabfrage; Authentik-Anmeldung bleibt bis zur Gerätefreigabe gesperrt
- eigener schmaler Device Service, weil Authentik Endpoint Devices noch keinen stabilen Android-Agenten anbietet
- Nextcloud-Talk-Handoff an freigegebene Konferenzgeräte; übertragen wird ausschließlich der Raumtoken
- optionale FCM-Hinweise für Mail, Termine, Talk und Gerätesicherheit ab Android 13
- FCM transportiert nur eine Ereignis-ID; Details holt ein freigegebenes Gerät signiert von der eigenen Bridge
- Datenschutzstufen `Diskret`, `Standard` und `Ausführlich`; Shared Tablets erzwingen neutrale Hinweise
- Sperrbildschirm zeigt unabhängig von der Stufe keine Absender, Betreffzeilen, Termin- oder Talk-Inhalte
- Bridge-Container mit Gerätefreigabe, Authentik-UserInfo-Prüfung, verschlüsselten FCM-Kennungen und terminierter Zustellung
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
ML_DEVICE_SERVICE_BASE_URL=https://device.mission-leben.de
ML_WEB_ALLOWED_HOST_SUFFIXES=mission-leben.de
ML_FIREBASE_APPLICATION_ID=1:1234567890:android:…
ML_FIREBASE_API_KEY=AIza…
ML_FIREBASE_PROJECT_ID=mission-leben-portal
ML_FIREBASE_SENDER_ID=1234567890
```

`ML_DEVICE_SERVICE_BASE_URL` bleibt standardmäßig leer. Dann funktionieren Authentik-Anmeldung und App-Portal, die Geräteregistrierung und Talk-Übergabe werden aber als noch nicht konfiguriert angezeigt.

`ML_WEB_ALLOWED_HOST_SUFFIXES` ist eine kommaseparierte Liste kontrollierter Domain-Endungen. Standardmäßig dürfen ausschließlich `mission-leben.de` und dessen Subdomains im In-App-Webcontainer laufen. SaaS- oder Fremdlinks öffnen außerhalb des Containers.

FCM bleibt vollständig deaktiviert, solange einer der vier `ML_FIREBASE_*`-Werte fehlt. Diese Firebase-App-Kennung ist Client-Konfiguration, kein Servergeheimnis. Das Firebase-Dienstkonto für den Versand darf dagegen niemals in Gradle-Properties, APK oder Git-Repository liegen. Die Einrichtung ist in [docs/FCM_SETUP.md](docs/FCM_SETUP.md) beschrieben.

`ML_DEVICE_SERVICE_BASE_URL` zeigt im Pilot auf den eigenen Bridge-Container. Er läuft als separater Dienst hinter TLS und nicht innerhalb des Authentik-Containers.

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
bridge/
  src/        Geräte-, Ereignis-, FCM- und Zimbra-Bridge
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
