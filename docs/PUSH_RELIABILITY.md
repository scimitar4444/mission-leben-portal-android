# Push-Zustellung auf Android

Mission Leben Zentral hält für ntfy einen `remoteMessaging`-Vordergrunddienst mit
stiller Dauerbenachrichtigung. Der Dienst verbindet nach Netzunterbrechungen
erneut, verwendet `START_STICKY` und startet nach Boot und Paket-Update.

Ab 0.12.18 prüft die App in **Einstellungen → Benachrichtigungen → Push-Zustellung**:

- ob die App und ihr Verbindungskanal Benachrichtigungen anzeigen dürfen,
- ob die Android-Akku-Ausnahme aktiv ist,
- ob eine lokale ntfy-Registrierung vorliegt.

Nach der Anmeldung erscheint bei fehlender Freigabe einmalig ein Hinweis.
Die Android-Akku-Ausnahme wird nur über den sichtbaren Systemdialog und mit
Entscheidung der Person gesetzt. Für Samsung öffnet ein zusätzlicher Knopf die
Herstellerliste „Apps, die nie im Standby sind“; bei TCL führt er zu den
App-Einstellungen und erklärt die optionale Sperre in der App-Übersicht.
Die App kann herstellereigene Schutzlisten nicht zuverlässig auslesen oder
unbemerkt setzen. Die Anzeige ist keine Live-Verbindungsprüfung und keine
Garantie für sofortige Zustellung. Eine vom System oder Nutzer vollständig
gestoppte App kann ihre eigene Verbindung nicht selbst wieder starten.

Für Abnahmetests: Registrierung und Benachrichtigungsfreigabe prüfen, Bildschirm
mindestens 45 Minuten ausschalten, ntfy-Verbindung serverseitig beobachten,
Testnachricht zustellen und anschließend das Öffnen aus der Benachrichtigung
prüfen. Mindestens ein TCL- und ein aktuelles Samsung-Gerät getrennt testen.

Die direkte Anfrage nach der Akku-Ausnahme ist nur für den Push-Kernfall
vorgesehen. Wird die App später über Google Play verteilt, ist die dann
geltende Play-Richtlinie erneut zu prüfen; Android Lint weist darauf hin.
