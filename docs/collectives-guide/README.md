# Aktive Kollektiv-Anleitungen

Am 12.10.2026 wurde die vorhandene Seite **Android-App** (95685099) von einem
Wegweiser zu einer aufgabenbezogenen App-Hilfe erweitert. Sie enthält kurze
Bedienwege, Kapitel-Sprunglinks, Fehlerhilfe und fünf kleine Bedienbilder.
Die verknüpften Installationsseiten und ihre Anhänge bleiben unverändert.
Die Hilfe berücksichtigt auch die lokal umgesetzten Favoriten, die Glocke,
„Problem melden“ und den neuen Hilfe-Einstieg. Deren OTA-Veröffentlichung ist
weiterhin ein separater Schritt; die Seite nennt deshalb mögliche Unterschiede
zwischen App-Versionen, ohne einen bereits erfolgten Rollout zu behaupten.

Die neuen Bilder in `help-images/` sind neutrale Orientierungsbilder, keine
Screenshots einer echten Sitzung. `render_help_images.py` verwendet dafür die
originalen VectorDrawable-Pfade der App; es ändert keine Screenshot-Sperre und
enthält keine persönlichen Daten. Die PNGs sind 450 Pixel breit und höchstens
186 Pixel hoch. SVG-Quellen und Renderer bleiben zur Nachpflege erhalten.
`update_app_help.php` schreibt ausschließlich die bestehende Hilfeseite und
deren neue Anhänge über die Nextcloud-Datei-API, mit Datei-ID-/Pfad-/Hash-Prüfung.
Der vorherige Text und die Verifikation stehen in `artifacts/app-help-20261012/`.
Es werden keine Berechtigungen, Flows oder App-Pakete geändert.

Wichtig bei weiteren Hilfetext-Änderungen: AppData-`ISimpleFile.putContent()`
aktualisiert nicht automatisch den kollaborativen Nextcloud-Text-Sitzungsstand.
Am 12.10.2026 sah die Android-App deshalb noch den alten Text, obwohl die Datei
und die Browser-Leseansicht bereits aktuell waren. `update_app_help.php` prüft
nun ungespeicherte Text-Änderungen und setzt bei abweichendem Inhalt gezielt nur
den Text-Sitzungsstand dieser Datei über `DocumentService.resetDocument()`
zurück, niemals mit `force`. Die einmalige Reparatur ist in
`reset_help_text_session.php` mit festem Datei-ID-/Pfad-/Hash-Ziel dokumentiert;
vor dem Reset werden der aktuelle Hilfetext, die drei zugehörigen Text-Tabellen
und der Yjs-Zustand in einem privaten, serverseitigen Kurzzeit-Backup gesichert.
Session-Tokens aus diesem Backup gehören niemals in Git oder Übergaben.
Eine bereits geöffnete alte Text-Ansicht muss anschließend neu geladen werden.

Der Nextcloud-Kopf wird durch `AppHelpLayout` nur im Android-WebView unter
`User-Guides-7` ausgeblendet, einschließlich des oberen Abstands. Der native
Portal-Zurück-Knopf und die Kollektiv-Navigation bleiben erhalten. Browser,
Authentik-Anmeldung und andere Nextcloud-Bereiche bleiben unverändert.

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
