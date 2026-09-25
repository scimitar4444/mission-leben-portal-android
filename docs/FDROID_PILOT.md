# F-Droid-Pilot für Mission Leben Zentral

Dieser Kanal ist ein **öffentlich lesbares, selbst betriebenes F-Droid-Binärrepository**, nicht der offizielle F-Droid-Katalog. Er enthält ausschließlich lokal mit dem bestehenden Produktionszertifikat signierte APKs. Der private Index-Schlüssel und der private APK-Signierschlüssel bleiben außerhalb von GitHub. Version 0.12.14 (Code 72) ist zunächst ein Pilot; das reguläre GitHub-OTA-Release bleibt 0.12.13.

## Repository auf dem Testgerät hinzufügen

1. F-Droid von [f-droid.org](https://f-droid.org/) installieren. Die Herkunft der F-Droid-App prüfen.
2. Auf dem Handy [das Mission-Leben-Repository öffnen](https://fdroid.link/#https://scimitar4444.github.io/mission-leben-portal-android/fdroid/repo?fingerprint=FD960CBC5B2DC1F860309B5F5A93D0FDB786C01AA5534E769397E96965EA251F) oder den QR-Code auf der [Repository-Seite](https://scimitar4444.github.io/mission-leben-portal-android/fdroid/repo/) scannen.
3. In F-Droid das Repository bestätigen und die Paketliste aktualisieren. Angezeigt werden muss **Mission Leben Zentral**. Das Repository-Zertifikat hat den SHA-256-Fingerabdruck `FD:96:0C:BC:5B:2D:C1:F8:60:30:9B:5F:5A:93:D0:FD:B7:86:C0:1A:A5:53:4E:76:93:97:E9:69:65:EA:25:1F`.
4. Die erste Aktualisierung auf 0.12.14 in F-Droid bewusst starten und alle Android-Abfragen prüfen. Die vorhandene App darf dabei **nicht deinstalliert** werden. Danach Registrierung, Anmeldung, Zimbra/Talk und Benachrichtigungen testen. Die Einstellung der App soll „Updates über F-Droid“ anzeigen.
5. In F-Droid „Automatische Aktualisierungen“ einschalten. Für den Test auf 0.12.16 weder die Paketliste manuell aktualisieren noch eine Installation starten; beobachten, ob F-Droid beides selbst erledigt. Anschließend denselben Ablauf auf einem Samsung-Gerät prüfen.

Teststand 25.09.2026: Die erste Aktualisierung auf 0.12.14 wurde vom Pilotnutzer als funktionierend bestätigt. 0.12.15 und 0.12.16 wurden durch F-Droid installiert. Bei 0.12.16 startete F-Droid am 25.09. um 06:31 automatisch Download und Installation, aber Android verwarf die Installationssitzung mit `INSTALL_FAILED_VERIFICATION_FAILURE`; gleichzeitig zeigte Play Protect „App-Scan empfohlen“, weil es diese Version noch nicht kannte. Nach manuellem Scan und Bestätigung wurde 0.12.16 installiert, und der Installer of Record ist `org.fdroid.fdroid`. Eine zuverlässig unbeaufsichtigte Aktualisierung ist damit nicht bestätigt; der Samsung-Test bleibt offen.

Die App erkennt eine Installation durch `org.fdroid.fdroid` oder `org.fdroid.basic` und unterdrückt dann ihren eigenen GitHub-OTA-Check. Andere Installationen bleiben beim bisherigen OTA-Weg. Das ist kein Versprechen, dass Play Protect oder Samsung „Automatische Sperre“ nie nachfragen; diese Entscheidungen liegen außerhalb der App. F-Droid kann auf neueren Android-Versionen nach einer ersten bestätigten Installation/Aktualisierung weitere Aktualisierungen im Hintergrund ausführen, was hier erst praktisch verifiziert werden muss.

## Isolierter Berechtigungstest 0.12.17

Der Pilot-Zweig `fdroid-permission-pilot` baut Version 0.12.17 (Code 75) ohne `REQUEST_INSTALL_PACKAGES`. Damit prüfen wir, ob diese für den alten App-eigenen OTA-Weg benötigte Berechtigung die Play-Protect-Rückfrage beim nächsten F-Droid-Update beeinflusst. Ein Ausbleiben oder Wiederkehren der Rückfrage ist ein Gerätetest, kein Beweis für eine allgemeine Google-Regel. Den Pilot-Zweig nicht als reguläres GitHub-OTA-Release veröffentlichen: Dort bleibt `releases/latest/download/update.json` vorerst auf 0.12.13 (Code 71), und diese Installationen benötigen weiterhin den App-eigenen Installationsweg.

Veröffentlicht am 25.09.2026 ausschließlich im F-Droid-Pilotrepository (`gh-pages`-Commit `6036fc1`). Signatur, Paketname, Code 75, Berechtigungsliste und alle öffentlichen Index-/Diff-/APK-Hashes wurden geprüft. Der Hintergrund-Installationsversuch und die Play-Protect-Reaktion für 0.12.17 sind noch **offen**; am Pilotgerät ist unmittelbar nach Veröffentlichung weiterhin 0.12.16 installiert. Den F-Droid-Index für diesen Test nicht manuell aktualisieren.

## Veröffentlichung und Sicherheitsgrenzen

- Den Versionscode strikt erhöhen und dieselbe Paket-ID sowie dasselbe APK-Signierzertifikat beibehalten. Vor Veröffentlichung APK-Hash, Signatur, Versionscode und Tests prüfen.
- Das signierte APK in das **private** Arbeitsverzeichnis des F-Droid-Repositories übernehmen, dort den Index mit `fdroid update` neu erzeugen und signieren. Nur den Inhalt von `repo/` auf den statischen HTTPS-Host veröffentlichen. `config.yml`, `keystore.p12`, APK-Signierschlüssel und Passwörter dürfen nie in den öffentlichen Branch oder in Release-Artefakte gelangen.
- Beim Kopieren **alle** von `repo/entry.json` referenzierten Dateien übernehmen, insbesondere `repo/diff/*.json` für Folgeupdates. Vor dem Push mit `bash scripts/verify_fdroid_repo.sh /pfad/zum/oeffentlichen/branch/fdroid/repo` und nach dem Pages-Build mit `bash scripts/verify_fdroid_repo.sh https://scimitar4444.github.io/mission-leben-portal-android/fdroid/repo` alle Index-, Diff- und APK-Verweise prüfen. Das Fehlen der Differenzdateien führte beim ersten Folgeupdate am 24.09.2026 zu „No files found“ und wurde im `gh-pages`-Commit `bdef5e3` korrigiert.
- Der öffentliche Host ist GitHub Pages, Branch `gh-pages`, Pfad `fdroid/repo/`. Vor dem Push die Liste aller Dateien im Commit prüfen. Der Index-Fingerabdruck im Einrichtungslink muss zum lokalen Index-Zertifikat passen.
- Die APK-Datei im Repository muss mit dem Produktionszertifikat `2E:C2:3C:AE:1A:61:DF:07:DC:B5:8F:CF:CD:9F:4F:6E:92:CA:1C:73:41:9F:6B:4C:D4:AD:D3:B5:E9:46:AC:00` signiert sein. Ein zusätzlicher Repository-Schlüssel signiert nur den F-Droid-Index; er ersetzt **nicht** die APK-Signatur.
- Den F-Droid-Index-Schlüssel vor einem breiten Rollout verschlüsselt sichern und eine Wiederherstellung testen. Ohne diesen Schlüssel kann der Kanal nicht nahtlos fortgeführt werden.

## Rückweg

Bis zur zweiten erfolgreichen Pilotaktualisierung keine automatische Umstellung aller Geräte vornehmen. Falls der Pilot scheitert, das F-Droid-Repository nicht weiter befüllen und die regulären Geräte auf GitHub-OTA lassen. Eine bereits installierte Pilotversion kann nur mit einem **höheren** Versionscode und derselben APK-Signatur auf den regulären Kanal zurückgeführt werden; ein Downgrade auf 0.12.13 ist ohne Datenverlust nicht vorgesehen. Versionscode und Signatur allein belegen aber noch keinen Kanalwechsel: Danach am Gerät den Installer of Record, die Updateanzeige in der App und einen GitHub-OTA-Check prüfen. Die App-Daten und Geräteidentität dürfen für den Rückweg nicht gelöscht werden.
