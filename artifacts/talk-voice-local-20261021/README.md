# Talk-Sprachnachrichten – lokaler Pilot 0.23.4-local/99

Nur für den vom Nutzer beauftragten Test. Kein OTA, kein produktiver Servereingriff,
keine Änderung an Authentik-Gruppen, Flows oder Sitzungsregeln. Öffentlich bleibt
0.23.3/98. Die fremde lokale Änderung in bootstrap_endpoint_devices.py bleibt unberührt.

Neue Android-Berechtigung: RECORD_AUDIO, erst bei einer zulässigen Talk-Anfrage
über den Android-Dialog erfragt. Kein CAMERA, kein Mikrofon-Foreground-Service.
Nur exakt HTTPS nextcloud.mission-leben.de, Port443 oder Standardport, zulässiger
Talk-Pfad, aktive App-Inhaltsansicht und entsperrte/registrierte Sitzung. Anfragen
mit Kamera, MIDI, unbekannten oder gemischten Ressourcen werden vollständig
abgewiesen. Auch nach bestehender Android-Zustimmung werden andere Apps/Origins
nicht freigegeben. Kein generisches request.grant(request.resources).

Eine ausstehende Mikrofonanfrage bleibt an ihre ursprüngliche Dokument-URL
gebunden. Seitenwechsel, Browser-Abbruch, Sperre und Stop verwerfen sie. Ein
verspätetes Android-Ergebnis darf keine neue Anfrage bedienen. Der Android-Dialog
selbst wird von einem echten App-Wechsel unterschieden; Erteilung wartet auf
Rückkehr in die sichtbare Ansicht. Alte WebViews ohne Document-Start-Injektion
bleiben für das Mikrofon gesperrt.

Der ursprüngliche globale Mikrofon-Button-Selektor versteckte auch Talk-Nachrichten.
Er ist auf den Talk-Dashboard-Anrufbereich eingegrenzt. Anrufschaltflächen und
Kamera bleiben gesperrt; externe Konferenzlinks behalten ihren bisherigen Weg.
Das Mikrofon ist technisch eine originbezogene Audiofreigabe, nicht eine vom
Browser beweisbare Unterscheidung zwischen Sprachdatei und Audiotelefonie.
Die Talk-eigene Oberfläche wird weiterhin auf Chat beschränkt.

Document-Start-Skript erfasst laufende/getUserMedia-Ströme, erlaubt nur Audio
im sichtbaren Hauptdokument und verwirft verspätete Ströme nach Kontextwechsel.
Beim Verlassen/Fokusverlust ruft es zuerst Talks Abbrechen-Knopf auf und stoppt
dann die Mikrofonspuren. Erst native Rückkehr in die zulässige Ansicht schaltet
Aufnahmen wieder frei. Keine neue native Audio-Dateiablage oder eigener Upload.

Wichtiger Updatevertrag zu Talk25.0.6 (installierte Version read-only bestätigt):
`src/components/NewMessage/NewMessageAudioRecorder.vue` verwendet
`.audio-recorder button:has(.close-icon)` für Abbrechen. Der Haken ruft stop auf
und sendet sofort; deshalb niemals diesen zum Hintergrund-Abbruch anklicken.
`abortRecording()` setzt aborted=true vor stop; generateFile lädt dann nichts hoch.
Selektor und Verhalten nach Talk-Updates erneut prüfen. Nextcloud wird nicht gepatcht.

Lokal geprüft: 430 Debug-/398 Release-Tests ohne Fehler oder Skips, neue reine
Permission-/Origin-/Lebenszyklusprüfungen und Node-Tests für Aufnahmewächter,
Abbrechen-vor-Stop, späte Resultate sowie bestehende Talk-Start-/Raumwege.
Am22.10.2026 als Update auf dem angeschlossenen Nutzerhandy installiert:
0.23.3/98 auf0.23.4-local/99, ohne Deinstallation; Paketversion und bestehender
Installationszeitpunkt zurückgelesen. Die App wartet auf menschliche Biometrie.
Reales Mikrofon, Android-Berechtigungsdialog, Aufnahme/Senden/Anhören und
Abbruch bei Bildschirm-Aus bleiben als Geräteabnahme offen. Kein OTA veröffentlicht.

## Auf dem Handy ausprobieren

1. Test-APK als Update installieren; bestehende Registrierung behalten.
2. Talk öffnen und einen geeigneten Testchat wählen.
3. Mikrofon antippen, Android-Freigabe selbst bestätigen und kurz sprechen.
4. Haken beendet UND sendet. X verwirft. Aufnahme beim Empfänger anhören.
5. Neue Aufnahme beginnen, Ansicht verlassen oder Bildschirm ausschalten:
   Mikrofon muss ausgehen, und es darf keine unbeabsichtigte Nachricht entstehen.
6. Freigabe auch einmal ablehnen: Textchat muss weiterhin funktionieren.

Vor Rollout diese echten Prüfungen durchführen. Rückweg ohne Datenlöschung:
vorherige Funktion mit höherem versionCode neu signieren oder vorwärts korrigieren;
kein stilles Downgrade/Deinstallieren, keine Server-Datenbank-Rücksicherung.
