# Produktionssignierung

Ab Version 0.10.0 verwendet Mission Leben Zentral einen dauerhaften Produktionsschlüssel. Sein öffentliches Zertifikat hat den SHA-256-Fingerabdruck:

```text
2E:C2:3C:AE:1A:61:DF:07:DC:B5:8F:CF:CD:9F:4F:6E:92:CA:1C:73:41:9F:6B:4C:D4:AD:D3:B5:E9:46:AC:00
```

`geraete.mission-leben.de/.well-known/assetlinks.json` darf für das Paket `de.missionleben.portal` ausschließlich den Fingerabdruck eines aktuell freigegebenen Produktionszertifikats ausliefern. Der Fingerabdruck ist öffentlich; der private Schlüssel und sein Kennwort sind es niemals.

## Lokale Vertrauenswurzel

Die lokale Release-Umgebung erwartet:

- einen PKCS#12-KeyStore außerhalb des Git-Repositories,
- Alias `mission-leben-zentral`,
- eine nur für den Besitzer lesbare Kennwortdatei,
- die vier `ML_ANDROID_KEYSTORE_*`-Umgebungsvariablen aus `docs/OTA_UPDATES.md`.

Vor dem ersten produktiven Release müssen KeyStore und Kennwort gemeinsam verschlüsselt auf ein organisatorisch kontrolliertes Offline-Medium kopiert und von dort testweise gelesen werden. Eine Kopie auf demselben Rechner ist kein Backup. Ohne den privaten Schlüssel können bestehende Installationen keine nahtlosen OTA-Updates mehr erhalten.

## Einmaliger Wechsel vom Pilot

Die Pilotversionen bis 0.9.2 wurden mit einem Android-Debugzertifikat verteilt. Android akzeptiert 0.10.0 deshalb nicht als Update über diese Installation. Auf den wenigen Pilotgeräten ist einmalig erforderlich:

1. bestehendes Gerät in Authentik sperren beziehungsweise löschen,
2. Mission Leben Zentral deinstallieren,
3. 0.10.0 mit dem Produktionszertifikat installieren,
4. Gerät über den neuen Ein-QR-Ablauf registrieren.

Alle nachfolgenden Releases müssen mit demselben Produktionsschlüssel signiert werden.
