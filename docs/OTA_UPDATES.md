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

Android verlangt bei einer nicht über Google Play oder eine Geräteverwaltung verteilten App einmal die Freigabe **Unbekannte Apps installieren** für Mission Leben Zentral. Ab Version 0.12.10 übergibt die App ein geprüftes APK an eine `PackageInstaller`-Sitzung und bittet Android bei einem Selbstupdate um Installation ohne erneute Bestätigung. Das ist eine Bitte, keine Garantie: Wenn Android `STATUS_PENDING_USER_ACTION` meldet, wird die echte Systembestätigung angezeigt. Manche Samsung-Geräte können zusätzlich durch „Automatische Sperre“ oder Play Protect eingreifen. Scheitert bereits die Sitzungserstellung auf einem Gerät, bleibt der bisherige sichtbare Installer als Rückfall erhalten. Ein Fehler nach dem Start der Sitzung wird beim nächsten Öffnen der App angezeigt.

Vor einem breiten Rollout sind auf einem typischen Samsung-Gerät zwei aufeinanderfolgende, identisch signierte Versionen zu testen: erst die Version mit dem neuen Installer aufspielen, dann das nächste Update über die App. Dabei einmal mit „Automatische Sperre“ an und einmal aus prüfen, ob Android tatsächlich ohne Dialog aktualisiert, ob der Bestätigungsrückfall funktioniert und ob Registrierung/Sitzung nach dem Update erhalten bleiben. Bis dahin ist ein bestätigungsfreies Update **nicht** als verifiziert zu bezeichnen.

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
  --tag v0.12.10 \
  --output update.json
gh release create v0.12.10 mission-leben-zentral.apk update.json \
  --repo scimitar4444/mission-leben-portal-android \
  --title 'Mission Leben Zentral 0.12.10' \
  --notes 'Verbesserte, signaturgeprüfte OTA-Selbstupdates.'
```

Bestandsinstallationen ab 0.7.8 erkennen spätere Releases selbst. Das erste Update auf 0.12.10 kann je nach Gerät noch eine Android-Bestätigung erfordern; die neue `PackageInstaller`-Sitzung ist erst in 0.12.10 vorhanden und kann daher frühestens beim darauffolgenden Update genutzt werden.

Der Signierschlüssel ist die dauerhafte Vertrauenswurzel. Geht er verloren, können bestehende Installationen nicht mehr nahtlos aktualisiert werden. Er benötigt deshalb mindestens eine verschlüsselte Offline-Sicherung mit dokumentiertem Wiederherstellungstest.
