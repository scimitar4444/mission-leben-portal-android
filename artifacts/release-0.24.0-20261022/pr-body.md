## Zusammenfassung

Closes #16. Vom Nutzer beauftragtes OTA0.24.0/100 nach realem lokalem Sprachpilot.

- Talk-Sprachnachrichten mit strikt origin-/audio-/vordergrund-/sitzungsgebundenem Mikrofonzugriff.
- Neue Android-Berechtigungen RECORD_AUDIO und MODIFY_AUDIO_SETTINGS; kein CAMERA und kein Mikrofon-Hintergrunddienst.
- Laufende Aufnahmen beim Verlassen zuerst über Talks Abbrechen-Knopf verwerfen, dann Mikrofonspuren stoppen. Kamera und eingebettete Anrufe bleiben eingeschränkt.
- Document-Start-Aufnahmewächter; Bereitschaft an Dokumentgeneration statt an instabile Talk-SPA-URL-Gleichheit gebunden. Verspätete Anfragen/Ströme werden verworfen.
- Optionaler, Talk-abhängiger Assistentenschritt und kompakte Freigabekarte in Einstellungen → Talk; bestehende Schritte bleiben erledigt. Textchat ohne Zustimmung weiterhin nutzbar.
- Code100 liegt über öffentlicher98 und Pilot99. Versionshinweise in allen9 Sprachen.

## Tests und Sicherheitsprüfung

- Finaler Clean-Archiv-Build:434 Debug-/402 Release-Tests und Node-Verträge erfolgreich; Lint0 Fehler/131 bekannte Warnungen. Alle fünf GitHub-CI-Prüfungen erfolgreich (Run39730169049).
- Menschlich am echten Gerät bestätigt: Aufnahme, Senden, Anhören, direkter Android-Mikrofondialog im Assistenten sowie X/Home ohne unbeabsichtigte Nachricht.
- Auch Bildschirm-Aus vom Nutzer bestätigt: ungesendete Aufnahme weg, bereits gesendete Nachricht bleibt erhalten. Android-AppOps belegt beendete Mikrofonverwendung.
- Quellprüfung der Origin-/Ressourcen-/Session-/Abbruchgrenzen durch implementierenden Agenten; kein unabhängiges Human-Review behauptet.
- Keine Authentik-/Gruppen-/Flow-/Bridge-/Nextcloud-Serveränderung. Fremde lokale Bootstrap-Änderung ausdrücklich ausgeschlossen.

## Ausbringung und Rückweg

Erst nach grünen Prüfungen produktiv signierte APK und passendes update.json als
reguläres Latest veröffentlichen; anonyme HTTPS-Downloads, Hash, Signatur und
Manifestbindung nachprüfen. Ein Release bedeutet nicht, dass die Flotte es bereits
installiert hat. 0.23.3 bleibt erhalten; Rückweg über gleich signierten Vorwärtsfix,
kein stilles Downgrade, keine Deinstallation/Datenlöschung.
