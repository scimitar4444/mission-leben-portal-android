## Ziel

Talk-Sprachnachrichten und die optionale Mikrofonfreigabe als OTA0.24.0 veröffentlichen.
Ausdrücklicher Nutzerauftrag: „ota veröffentlichen“, Version „0.24.0“.

## Umfang und Sicherheitsgrenzen

- Mikrofon nur für Audio im sichtbaren, entsperrten Talk-Container auf der exakten Nextcloud-Origin.
- Kein CAMERA, kein Mikrofon-Hintergrunddienst, keine allgemeinen Medienfreigaben.
- Bei Verlassen zuerst Talk-Aufnahme abbrechen, dann Mikrofonspuren stoppen; nichts ungewollt senden.
- Mikrofonfreigabe direkt im neuen, Talk-abhängigen Assistentenschritt und in Einstellungen → Talk.
- Kein Authentik-/Flow-/Gruppen-/Servereingriff; bestehende Registrierung und Sitzungsregeln bleiben erhalten.

## Nachweise

Lokaler Pilot:433 Debug-/401 Release-Tests und beide Node-Verträge bestanden.
Nutzer hat Aufnahme, Senden, Anhören, direkten Android-Freigabedialog im Assistenten,
Verwerfen mit X sowie Home-Geste ohne unbeabsichtigte Nachricht bestätigt.
Finaler Code100 erhält höhere Update-Nummer als öffentlicher98 und Pilot99.
Finaler Release-Build/CI, Signatur und öffentliche APK-/Manifest-Prüfung folgen vor Freigabe.

## Rückweg

0.23.3 behalten; bei Bedarf Latest zurücknehmen und einen gleich signierten
Vorwärtsfix mit Code größer100 veröffentlichen. Kein stilles Downgrade, keine Deinstallation.
