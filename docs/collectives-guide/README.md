# Aktive Kollektiv-Anleitungen

Diese Texte entsprechen den vorhandenen Seiten unter **User Guides → Mission
Leben Zentral**. Am 11.10.2026 wurden die Android-Anleitungen um bis zu zwei
persönliche Handys, die gezielte Geräte-Ersetzung und das Passwort-Auge ergänzt.
Das Auge wird als fertige Bedienfunktion beschrieben; die Veröffentlichung des
nächsten App-Releases ist ein separater Schritt. Die Zwei-Handy-Regel ist bereits
produktiv. Gruppenkonto-Diensthandys bleiben auf ein Handy begrenzt. Bestehende
Bilder, Links und Seiten-IDs bleiben erhalten. Die Erklärung vom 05.10.2026 zur
sicheren Bildschirmsperre und Gesichtserkennung bleibt erhalten: Android
muss die Biometrie als ausreichend sicher einstufen. Andernfalls wird der
Gerätecode verwendet, nicht das Mission-Leben-Passwort; die PIN-Abfrage ist kein
Fehler. Die App-Sicherheitslogik bleibt unverändert.

| Quelldatei | Vorhandene Kollektiv-Seite |
|---|---|
| `00-mission-leben-zentral.md` | Mission Leben Zentral, 94019508 |
| `00-webportal-im-browser.md` | Webportal im Browser, 95685097 |
| `00-android-app.md` | Android-App, 95685099 |
| `01-persoenliches-handy.md` | Persönliches Handy, 95685102 |
| `02-gemeinsames-tablet.md` | Gemeinsames Tablet, 95685139 |
| `03-geraet-einrichten.md` | Gerät einrichten – für Leitungen und IT, 95685145 |

Die `.attachments.<Seiten-ID>`-Bildpfade sind die vorhandenen Inline-Anhänge
in Kollektiv. Sie werden nicht umbenannt, verschoben oder neu angelegt.
Die drei Android-Seiten mit Bildern enthalten zusätzlich einen direkten Link
zum vorhandenen Anhang. So bleibt das Bild erreichbar, falls der Text-Viewer
die Inline-Darstellung nicht lädt. Die Bilddateien selbst bleiben unverändert.

Die drei öffentlichen Installationsansichten `/download`, `/setup` und
`/install` verwenden denselben Hinweis aus
`enrollment-portal/src/mission_leben_device_enrollment/templates/install_screen_lock_help.html`.

Der Hinweis gilt für gespeicherten Schnellzugang auf persönlichen und direkt
gebundenen Diensthandys. Ohne nutzbare sichere Bildschirmsperre bleibt die
Anmeldung nicht gespeichert; bei einer neuen App-Sitzung ist das Passwort
erneut nötig. Ein kurzer App-Wechsel ist keine neue Sitzung. Gemeinsame Tablets
behalten die bisherige neue Anmeldung nach Bildschirm-Aus. Die Samsung-
**Automatische Sperre** für APK-Installationen ist nicht die Bildschirmsperre.
