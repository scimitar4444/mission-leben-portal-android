# OTA-Updates

Mission Leben Zentral prüft beim Wechsel in den Vordergrund automatisch, höchstens einmal in sechs Stunden, ob im öffentlichen GitHub-Projekt ein neueres Release vorhanden ist. Die feste Manifest-Adresse lautet:

```text
https://github.com/scimitar4444/mission-leben-portal-android/releases/latest/download/update.json
```

Eine gefundene Version wird nur angeboten. Erst nach **Herunterladen und installieren** lädt die App das APK in ihren privaten Cache. Vor der Übergabe an Android prüft sie:

1. HTTPS und den fest hinterlegten GitHub-Repositorypfad,
2. Paketname `de.missionleben.portal`,
3. einen höheren `versionCode`,
4. die exakte Dateigröße und SHA-256-Prüfsumme aus `update.json`,
5. dass das APK mit demselben Zertifikat wie die installierte App signiert ist.

Android verlangt bei einer nicht über Google Play oder eine Geräteverwaltung verteilten App einmal die Freigabe **Unbekannte Apps installieren** für Mission Leben Zentral. Jede Installation bleibt eine sichtbare Android-Systemaktion. Eine unbeaufsichtigte stille Installation ist in diesem Betriebsmodus nicht vorgesehen.

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
  --tag v0.7.8 \
  --output update.json
gh release create v0.7.8 mission-leben-zentral.apk update.json \
  --repo scimitar4444/mission-leben-portal-android \
  --title 'Mission Leben Zentral 0.7.8' \
  --notes 'Automatische, signaturgeprüfte OTA-Updates.'
```

Der erste Wechsel von 0.7.7 auf 0.7.8 muss noch einmal manuell mit `adb install -r` erfolgen, weil 0.7.7 den Update-Checker noch nicht enthält. Ab 0.7.8 erkennt die App spätere Releases selbst.

Der Signierschlüssel ist die dauerhafte Vertrauenswurzel. Geht er verloren, können bestehende Installationen nicht mehr nahtlos aktualisiert werden. Er benötigt deshalb mindestens eine verschlüsselte Offline-Sicherung mit dokumentiertem Wiederherstellungstest.
