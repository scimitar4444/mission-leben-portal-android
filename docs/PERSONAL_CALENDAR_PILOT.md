# Persönlicher Zimbra-Kalender: Pilot und Freigabe

Stand: 29.09.2026. Die Bridge 0.10.7 wurde zunächst auf genau einen
Testnutzer begrenzt. Mit Bridge 0.10.8 entfällt diese Pilotsperre: Der
Kalenderzugriff richtet sich nach der dynamischen Authentik-Zuordnung und
dem persönlichen Gerätemodus. Die App 0.20.0 enthält die optionale
Kalenderfunktion. Eine erfolgreiche Bereitstellung ersetzt keinen Test auf
jedem Kalendertyp und Handyhersteller.

## Verhalten

- Optionaler **Einweg-Sync Zimbra → Handy** ausschließlich zur Anzeige der
  Termine im Android-Kalender und in Widgets. Es gibt keinen Rückkanal nach
  Zimbra. Neue oder geänderte Termine müssen in der Zimbra-Web-App entstehen.
- Die Einstellung ist nur auf einem persönlichen Gerät und nur bei effektivem
  Zimbra-Zugriff sichtbar. Der Authentik-Exporter erlaubt ausschließlich aktive
  physische Personen (`person`/`person`), nicht Shared- oder Gruppenkonten.
- Auswahl: 1 Tag, 3 Tage, 1 Woche oder 2 Wochen. Android speichert nur Termine
  im gewählten Zeitraum in einem eigenen, für Nutzer schreibgeschützten lokalen
  Kalender. Es legt keine zusätzlichen lokalen Erinnerungen an.
- Die Bridge übernimmt vollständige Zimbra-Snapshots, verschlüsselt Betreff und
  Ort in SQLite und gibt sie nur an registrierte persönliche Geräte mit
  gültiger Gerätesignatur und aktueller Berechtigung aus. Das Android-Gerät
  synchronisiert bei Aktivierung und danach ungefähr alle sechs Stunden; die
  tatsächliche Ausführung kann Android verschieben.
- Der fünfminütige Zuordnungsabgleich erfasst auch neu registrierte persönliche
  Geräte, wenn Push-Mitteilungen deaktiviert sind. Er übernimmt nur aktive
  Konten mit effektivem Zimbra-Zugriff. Die Zimbra-Kontenzuordnung enthält das
  boolesche `personal_calendar`; Shared-Konten erhalten hier `false`.
  Bridge und Worker prüfen diese Freigabe unabhängig. Fehlende Zuordnungen
  erlauben keinen Kalenderzugriff.
- Abmelden, Profilwechsel, Geräteblock oder bestätigter Berechtigungsentzug
  entfernen den lokalen Kalender. Ein bloßer Netzfehler löscht keine Termine.

## Grenzen und Geräteprüfung

- Android-Kalender-Apps mit Kalender-Leseberechtigung können die synchronisierten
  Termine ebenfalls sehen. Bei manuell entzogener Kalenderberechtigung kann die
  App die lokalen Einträge nicht mehr selbst löschen, bis die Berechtigung
  wieder erteilt wird.
- Ab App-Version 0.21.6 unterstützen SOAP-Abfrage, Worker, Bridge und Android
  gemeinsam bis einschließlich 200 Termine. Die Abfrage fordert 201 Datensätze an und prüft
  zusätzlich Zimbras `more`-Kennzeichen. Bei mehr als 200 Termininstanzen oder
  unvollständiger Antwort bleibt der letzte Stand erhalten; es wird niemals
  eine abgeschnittene Liste als vollständiger Kalender übernommen.
  Alte Apps erhalten für Kalender über 100 Terminen vorübergehend HTTP 503
  statt einer gekürzten Liste; ihre lokalen Termine bleiben dabei erhalten.
  Kalender bis 100 Termine bleiben mit alten Apps nutzbar. Server und App müssen
  für diese Erweiterung gemeinsam ausgerollt und live geprüft werden.
- Für die weitere Geräteabnahme: Termine hinzufügen/ändern/löschen,
  Offline-Verhalten, Abmelden/Blockieren sowie Samsung-Kalender und Widgets
  prüfen. Ganztägige und wiederkehrende Termine sind besonders zu beachten.
- Ein Debug-APK hat eine andere App-ID und ersetzt die installierte Release-App
  nicht. Für einen Test auf dem bestehenden persönlichen Handy ist ein
  passend signierter Pilot-Build nötig; kein OTA-Release allein aus lokalem
  Build ableiten.

## Erster Gerätetest

Beim ersten Einschalten des Syncs stürzte die App ab: Dem neuen JobScheduler-Job
mit Netzbedingung fehlte `ACCESS_NETWORK_STATE`. Das Manifest wurde korrigiert,
Release-Tests/Lint/Build erneut ausgeführt und die App mit demselben
Produktionszertifikat per `adb install -r` ersetzt. Version und Code bleiben
0.12.21/79; vorhandene App-Daten und Kalenderberechtigungen blieben erhalten.
Danach lieferte die Bridge erfolgreich Termine an die App. Durch gleichzeitig
laufende Initialabgleiche entstanden zwei gleiche, eindeutig App-eigene
Kalender. Eine prozessweite Schreibsperre und gezielte Bereinigung solcher
Duplikate wurden eingebaut und erneut installiert. Auf dem Pilot-Handy blieb
danach genau ein eigener, sichtbarer und für Nutzer schreibgeschützter Kalender.
Die vier Zeiträume wurden über die App umgestellt; lokal ergaben sich 2, 10,
12 beziehungsweise 20 Termine. Ausschalten entfernte den eigenen Kalender,
erneutes Einschalten stellte genau einen Kalender mit 20 Terminen wieder her.
In aCalendar+ erscheint „Mission Leben · Zimbra“ in der Kalenderliste als
aktiviert. Google Kalender listete diesen lokalen Kalender auf dem Pilot-Handy
dagegen nicht; die Anzeige ist also kalenderappabhängig. Fremde Kalender wurden
nicht berührt. Noch nicht abgenommen sind die tatsächliche Terminanzeige und
ein Widget, Änderungen/Löschungen in Zimbra, Offline-Verhalten, ganztägige und
wiederkehrende Termine sowie Abmelden/Device-Block und Berechtigungsentzug.

Die horizontale Zeitraumwahl und das Öffnen eines Talk-Besprechungslinks aus
Zimbra in der installierten Talk-App wurden auf dem Pilot-Handy mit 0.12.21
bestätigt. Für 0.12.22 sind Build, CI, Signatur, OTA-Manifest und öffentlicher
APK-Download geprüft; die Installation über den OTA-Dialog ist noch nicht
physisch bestätigt.
