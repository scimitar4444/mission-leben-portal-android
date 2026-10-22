# OTA0.24.0/100 – veröffentlicht und öffentlich geprüft

Am22.10.2026 um07:55:03 Uhr (Europe/Berlin) als reguläres Latest veröffentlicht:
https://github.com/scimitar4444/mission-leben-portal-android/releases/tag/v0.24.0

Der Nutzer beauftragte ausdrücklich das OTA und nannte Version0.24.0.
Code100 liegt über dem öffentlichen98 und dem lokalen Pilot99; auch Pilotgeräte
können auf das öffentliche Release aktualisieren. Gerätebindung und Anmeldung
werden nicht gelöscht. Keine Server-, Authentik-, Flow- oder Gruppenänderung.

Enthalten sind Talk-Sprachnachrichten und die optionale Mikrofonfreigabe direkt
im Assistenten sowie unter Einstellungen → Talk, nur bei sichtbarer Talk-App.
Textchat bleibt ohne Zustimmung nutzbar. Kamera und eingebettete Anrufe bleiben
eingeschränkt. Laufende Aufnahmen werden beim Verlassen verworfen, bevor die
Mikrofonspuren gestoppt werden. Keine neue native Aufnahmedateiablage.

Auf echtem Nutzerhandy bestätigt: Aufnahme, Senden, Anhören, Android-Freigabe über
den neuen Assistenten und Status erlaubt, X/Home ohne unbeabsichtigte Nachricht,
Bildschirm-Aus verwirft nur die ungesendete Aufnahme und erhält die gesendete.
Android-AppOps bestätigt beendete Mikrofonverwendung. Wachhaltung wieder0.
Die finale100 ist nicht zusätzlich direkt aufs Handy installiert worden; keine
Flotteninstallation behauptet. Seit dem akzeptierten Pilot nur Versionshinweise,
Code/Versionsname und eine äquivalente KTX-URI-Hilfsfunktion geändert.

Finaler Clean-Archiv-Build434 Debug-/402 Release-Tests, beide Talk-Node-Verträge,
Lint0 Fehler/131 bekannte Warnungen und alle5 PR-Prüfungen erfolgreich.
PR17 ist gemergt. Merge b49cc96 und gebauter Commit60b84f6 haben denselben Baum.
APK produktiv signiert, Signatur unverändert. Öffentliche APK und Latest-Manifest
anonym mit HTTPS/TLS-Prüfung vollständig geladen, bytegleich und signaturgeprüft.

Hashes, Abnahmeschranken und Rückweg: `release-evidence.json`.
Lokaler Fehlerverlauf und Talk-Updatevertrag:
`../talk-voice-local-20261021/README.md`.
Fremde lokale Bootstrap-Änderung weiterhin ausgeschlossen und unverändert.
