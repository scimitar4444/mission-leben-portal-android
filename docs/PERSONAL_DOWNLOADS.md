# Persönliche Dokumentdownloads

Ab App 0.21.2 / versionCode 85 werden bewusst angetippte Downloads aus
freigegebenen Web-Anwendungen auf einem bestätigten persönlichen
Mitarbeiterhandy im normalen Android-Ordner **Downloads** gespeichert.
Gemeinsame Tablets und Gruppenkonto-Diensthandys erhalten diese Exportfunktion
nicht. Ein Dokumenten-Push startet keinen Download.

Nach erfolgreichem Download öffnet die aktive, entsperrte Web-Ansicht die Datei
mit Android `ACTION_VIEW`. Android verwendet das passende Standardprogramm
oder bietet die Programmauswahl an. Im Hintergrund stellt DownloadManager eine
antippbare Fertigmeldung bereit. Ohne passenden Viewer bleibt die Datei in
Downloads; ein verständlicher Hinweis nennt **Dateien → Downloads**.

Der Viewer erhält ausschließlich die DownloadManager-`content://downloads/`-URI,
MIME-Typ und Lesefreigabe für die konkrete Datei. Er erhält keine Cookies, keinen
Portal-Link und keine Authentik-Token. HTTPS-/Domainprüfung sowie die bestehende
authentifizierte Downloadanfrage bleiben erhalten. Generische Server-MIME-Typen
werden anhand der Dateiendung ergänzt. Eindeutige Dateinamen vermeiden Konflikte
bei erneutem Download derselben Datei.

Voraussetzung für Export und automatisches Öffnen: persönlicher Browsermodus,
registrierter persönlicher Modus, `PERSONAL_EMPLOYEE`, `TRUSTED`, App-Inhalt,
keine erforderliche Neuanmeldung und keine lokale Sitzungssperre. Automatisches
Öffnen erfolgt nur bei aktiver Activity mit Fensterfokus. Ein abgeschlossener
Export bleibt nach Abmeldung, Profilwechsel oder Gerätesperre auf dem Handy;
laufende Jobs werden bei Bereinigung beendet. App-private Alt-Downloads werden
weiter wie bisher entfernt. Öffentliche Downloads gehören nicht zum löschbaren
Webcontainer und sind von dessen Fernbereinigung ausdrücklich ausgenommen.

Regressionen prüfen Profile/Sperrzustände, URI-/MIME-Übergabe, eindeutige Namen,
app-eigene Download-IDs sowie den Erhalt fertiger Dateien beim Abmelden.
Physisch zu prüfen: PDF im Vordergrund öffnen; im Hintergrund die Fertigmeldung
antippen; ohne passenden Viewer Hinweis und Dateiablage; Abmelden während eines
Downloads; erfolgreiche Datei nach Abmeldung weiterhin vorhanden.

## Bestehende Registrierungen ab 0.21.3

Registrierungen aus Versionen vor Einführung von `EnrollmentProfile` hatten
teilweise kein lokal gespeichertes Profil. 0.21.2 ließ sie deshalb korrekt
nicht in den öffentlichen Exportweg und speicherte weiterhin App-privat.

Ab 0.21.3 übernimmt der normale authentifizierte Geräte-Statusabruf das bereits
vom Portal zurückgegebene `enrollment_profile`. Erst nach Prüfung der exakten
Geräte-ID und der Übereinstimmung mit dem Gerätemodus wird das Profil lokal
gespeichert. Es ist keine neue Registrierung und keine Serveränderung nötig.
Ein fehlender Marker erzeugt keine Mitarbeiterberechtigung; unbekannte oder
widersprüchliche Profile werden abgewiesen. Gruppenkonto- und Tabletprofile
bleiben ausdrücklich getrennt.

## Automatisches Öffnen einstellen

Angemeldete, bestätigte persönliche Mitarbeiterhandys zeigen unter
**Einstellungen → Dokumente** den Schalter **Downloads automatisch öffnen**.
Der Bereich ist zunächst zugeklappt und zeigt nur den aktuellen Status.
Antippen klappt Schalter und Erklärung auf; erneutes Antippen klappt sie zu.
Er ist standardmäßig eingeschaltet und bleibt bei App-Neustarts und Updates
erhalten. Gemeinsame Tablets und Gruppenkonto-Diensthandys sehen ihn nicht.

Ausgeschaltet speichert die App dieselben bewusst angeforderten Exporte in
Android Downloads; die Android-Fertigmeldung und manuelles Öffnen bleiben
verfügbar. Es wird kein externer Viewer automatisch gestartet. Bereits fertige
Downloads werden aus der automatischen Öffnungswarteschlange genommen, nicht
gelöscht. Noch laufende Jobs bleiben für die bestehende Abmelde-/Sperrbereinigung
erfasst. Die aktuelle Schalterstellung wird beim Abschluss geprüft, nicht nur
beim Start des Downloads. Erneutes Einschalten öffnet keine zuvor erledigten
Dateien nachträglich. Die Exportberechtigung wird durch den Schalter niemals
erweitert.
