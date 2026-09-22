# Kompakte Startseite ab 0.12.4

Reihenfolge: Marke/Einstellungen, persönliche Begrüßung, wichtige Hinweise,
Kontakte, Apps, Aktuelles, Geräte-/Sitzungsstatus. Geburtstagsgruß,
Biometrie-Einrichtung und abweichende Gerätezustände bleiben erhalten.

- Zwei App-Spalten, mindestens 72 dp hohe Zeilen; bei großer Schrift wachsen
  die Kacheln mit. Ungelesen-Zähler liegen am Symbol, nicht über dem App-Namen.
- Hinweise starten kompakt mit Betreff und Neu-Markierung. Auf-/Zuklappen
  quittiert nichts. Nur „Gelesen“ markiert den Hinweis als gelesen. Volltext und
  Zeilenumbrüche werden beim Aufklappen angezeigt. Ein veralteter Cache bleibt
  auch eingeklappt gekennzeichnet.
- Hinweise sind allgemeine Ankündigungen. Aus ihrem Text wird weder ein
  App-Ausfall geraten noch eine App deaktiviert oder eingefärbt.
- „Aktuelles“ zeigt die neueste Meldung unter den Apps. Die Gerätezeile öffnet
  Einstellungen; der Gültigkeitswert hat eine vollständige Screenreader-Beschreibung.
- Berechtigungen, Anmeldewege und Shared-Screen-off-Abmeldung sind unverändert.

`HomeOverviewTest` rendert ausschließlich synthetische Daten unter Robolectric:
3/6 Apps, aktiver Hinweis, 320/360 dp, Hell/Dunkel, Schriftfaktor 1,5,
Auf-/Zuklappen, Gelesen, neuer Hinweis, Cache-Warnung, Navigation und Capability-
Abgrenzung. Bilder entstehen unter `app/build/reports/home-layout/`.
Diese Tests gehören nur zur Debug-Testvariante und sind kein App-Debugzugang.

Ausführen: `./gradlew testDebugUnitTest lintDebug`.
Die Renderprüfung ergänzt, ersetzt aber keinen Test auf einem echten Gerät.
