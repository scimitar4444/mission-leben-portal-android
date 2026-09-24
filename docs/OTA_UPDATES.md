# OTA-Updates

Mission Leben Zentral prüft einmal bei jedem echten App-Start automatisch, ob im öffentlichen GitHub-Projekt ein neueres Release vorhanden ist. Das Zurückkehren aus einer Web-Anwendung löst innerhalb desselben App-Laufs keine weitere Prüfung aus. Über **Nach Updates suchen** kann die Prüfung jederzeit manuell erzwungen werden. Die feste Manifest-Adresse lautet:

```text
https://github.com/scimitar4444/mission-leben-portal-android/releases/latest/download/update.json
```

Eine gefundene Version wird nur angeboten. Erst nach **Herunterladen und installieren** lädt die App das APK in ihren privaten Cache. Vor der Übergabe an Android prüft sie:

1. HTTPS und den fest hinterlegten GitHub-Repositorypfad,
2. Paketname `de.missionleben.portal`,
3. einen höheren `versionCode`,
4. die exakte Dateigröße und SHA-256-Prüfsumme aus `update.json`,
5. dass das APK mit demselben Zertifikat wie die installierte App signiert ist.

Android verlangt bei einer nicht über Google Play oder eine Geräteverwaltung verteilten App einmal die Freigabe **Unbekannte Apps installieren** für Mission Leben Zentral. Ab Version 0.12.13 öffnet die App nach der APK-Prüfung wieder den sichtbaren Android-Installationsdialog. Die Versionen 0.12.10 bis 0.12.12 enthielten einen Versuch mit `PackageInstaller`-Sitzungen ohne erneute Nutzerbestätigung. Auf dem Pilotgerät verlangte Play Protect trotzdem eine zusätzliche Entscheidung; dieser Weg wurde daher zurückgenommen. Play Protect und auf Samsung-Geräten die „Automatische Sperre“ können weiterhin eingreifen. Ob Play Protect nur einen Scan anbietet oder eine Warnung anzeigt, entscheidet Android bzw. Google und ist durch die App nicht garantiert.

Beim Update von 0.12.10 oder 0.12.11 auf 0.12.13 führt noch die bisher installierte Version die Übergabe an Android aus. Dabei kann einmalig die Play-Protect-Abfrage des `PackageInstaller`-Piloten erscheinen. Alternativ kann die identisch signierte 0.12.13-APK direkt im Browser geladen und über den Android-Installer installiert werden. Erst ab der installierten 0.12.13 werden weitere OTA-Updates wieder direkt an den sichtbaren Installer übergeben. Für den Rollout sind Registrierung, Anmeldung und Sitzungserhalt nach dem Update zu prüfen.

## Release erstellen

Der private Signierschlüssel bleibt außerhalb von GitHub und darf weder als Repository-Datei noch als GitHub-Actions-Secret hochgeladen werden. Die vier Variablen werden nur in der lokalen Release-Umgebung gesetzt:

```bash
export ML_ANDROID_KEYSTORE_FILE=/sicherer/pfad/mission-leben-portal.keystore
export ML_ANDROID_KEYSTORE_PASSWORD='…'
export ML_ANDROID_KEY_ALIAS='…'
export ML_ANDROID_KEY_PASSWORD='…'
./gradlew clean testReleaseUnitTest lintRelease assembleRelease
```

Danach wird das APK unter einem stabilen Asset-Namen abgelegt und das Manifest aus den von Gradle erzeugten Metadaten erstellt:

```bash
install -m 0644 app/build/outputs/apk/release/app-release.apk mission-leben-zentral.apk
python scripts/create_update_manifest.py \
  --apk mission-leben-zentral.apk \
  --metadata app/build/outputs/apk/release/output-metadata.json \
  --tag v0.12.13 \
  --output update.json
gh release create v0.12.13 mission-leben-zentral.apk update.json \
  --repo scimitar4444/mission-leben-portal-android \
  --title 'Mission Leben Zentral 0.12.13' \
  --notes 'Signaturgeprüfte OTA-Updates mit sichtbarem Android-Installationsdialog.'
```

Bestandsinstallationen ab 0.7.8 erkennen spätere reguläre Releases selbst. Eine installierte 0.12.10 oder 0.12.11 nutzt für den Übergang auf 0.12.13 noch den `PackageInstaller`-Piloten.

Der Signierschlüssel ist die dauerhafte Vertrauenswurzel. Geht er verloren, können bestehende Installationen nicht mehr nahtlos aktualisiert werden. Er benötigt deshalb mindestens eine verschlüsselte Offline-Sicherung mit dokumentiertem Wiederherstellungstest.
