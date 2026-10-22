# Talk-Sprachnachrichten – lokaler Pilot 0.23.4-local/99

Nur für den vom Nutzer beauftragten Test. Kein OTA, kein produktiver Servereingriff,
keine Änderung an Authentik-Gruppen, Flows oder Sitzungsregeln. Öffentlich bleibt
0.23.3/98. Die fremde lokale Änderung in bootstrap_endpoint_devices.py bleibt unberührt.

Neue Android-Berechtigungen: RECORD_AUDIO, erst bei einer zulässigen Talk-Anfrage
über den Android-Dialog erfragt, sowie die normale Berechtigung MODIFY_AUDIO_SETTINGS
für die Chromium-Audioeingabe. Kein CAMERA, kein Mikrofon-Foreground-Service.
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

## Gerätefehler beim ersten Mikrofontest am22.10.2026

Der Nutzer erteilte die Android-Mikrofonfreigabe. PackageManager bestätigte
RECORD_AUDIO granted=true; Talk meldete trotzdem Mikrofonzugriff verweigert.
Auch erneutes Antippen im offenen Selbstchat scheiterte. Das App-Protokoll meldete
mehrfach: `Requires MODIFY_AUDIO_SETTINGS and RECORD_AUDIO. No audio device will
be available for recording`. Es wurde keine Nachricht versendet.

Erster nachgewiesener Fehler: Die zweite, normale Manifest-Berechtigung fehlte im ersten Pilot99.
Commit8c0a191 ergänzt ausschließlich MODIFY_AUDIO_SETTINGS und einen
Regressionscheck im vorhandenen Node-Test. Keine Lockerung der Origin-, Kamera-,
Sitzungs- oder Vordergrundprüfung. Chromium prüft beide Berechtigungen vor der
Eingabegeräteauswahl; siehe
https://raw.githubusercontent.com/chromium/chromium/main/media/base/android/java/src/org/chromium/media/AudioManagerAndroid.java
(`getAudioInputDeviceNames`). Die Android-Dokumentation stuft MODIFY_AUDIO_SETTINGS
als normale Installationsberechtigung ein:
https://developer.android.com/reference/android/Manifest.permission#MODIFY_AUDIO_SETTINGS

Audiofix am22.10.2026 um07:16:35 als Update installiert, unveränderte Registrierung.
Beide Audioberechtigungen sind granted=true. Der Chromium-Hinweis zur fehlenden
Berechtigung ist weg; der Nutzer meldet aber weiterhin denselben Talk-Fehler.
Dies ist ausdrücklich noch keine erfolgreiche Mikrofonabnahme. Commit311367f
ergänzt begrenzte Diagnosecodes und boolesche Gate-Zustände ausschließlich für
lokale Versionen; keine Inhalte, URLs, Identitäten oder Fehlertexte werden geloggt.

Zweiter Fehler am Gerät reproduziert: Beim Wechsel von der Talk-Übersicht in den
Selbstchat liefert onPageFinished teilweise noch die bisherige Route, während
WebView.url bereits die neue SPA-Route enthält. Diagnose um07:23:13:
`guard start=true installed=true samePage=false talk=true ready=false`.
Die nachfolgende Anfrage war korrekt scoped, entsperrt und im Vordergrund,
wurde aber wegen ready=false abgewiesen.

Commitfebf24b bindet den Bereitschaftsnachweis an eine pro Hauptdokumentnavigation
erhöhte Generation statt an URL-Gleichheit. Beide Routen müssen weiterhin exakt
zulässige Talk-Dokumente sein; verspätete Rückgaben eines ersetzten Dokuments
werden verworfen und dürfen auch keinen neueren Bereitschaftszustand überschreiben.
Zwei Regressionstests decken Raumwechsel innerhalb desselben Dokuments sowie
Dokumentersetzung, fremde Origins/Ports und nicht zulässige Seiten ab.

Korrigiertes Pilot-APK `mission-leben-zentral-0.23.4-local-routefix.apk` um07:25:30
als Update installiert. SHA256 b40721c45aaaab14d65292dde8b48a532d8810ba187e5fdce420f1d68b18b08c,
Produktionssignatur unverändert. Clean-Archiv-Build,432 Debug-/400 Release-Tests
und beide Node-Vertragstests grün; Lint0 Fehler/131 bekannte Warnungen. Echte
Aufnahmefunktion anschließend vom Nutzer mit „geht!“ bestätigt; Android-AppOps
bestätigt4,419 Sekunden echte Mikrofonverwendung, danach beendet. Senden/Anhören
und gezieltes Verwerfen/Hintergrund/Sperre bleiben separat zu prüfen. Nicht als
vollständig abgenommen oder OTA veröffentlicht ausweisen. Update-Verwalter über beide Fehler und den
erweiterten SPA-Abnahmevertrag informiert.

## Mikrofonfreigabe in Assistent und Einstellungen

Auf Nutzerwunsch ergänzt Commita3fb697 einen eigenen neuen Schritt TALK_VOICE.
Nur angemeldete Nutzer mit tatsächlich sichtbarer Talk-App sehen ihn, auch ohne
Push oder Zimbra. Bereits abgeschlossene Schritte bleiben erledigt; der neue
Schritt erscheint einmalig und kann später im Assistenten erneut geöffnet werden.
Unter Einstellungen → Talk erscheint dasselbe kompakte Panel zum Aufklappen.

„Mikrofon erlauben“ öffnet direkt den Android-Berechtigungsdialog. Es gibt keinen
zweiten internen Schalter und keine automatische Aufnahme. Der angezeigte Status
kommt aus Android und wird nach Rückkehr aus den Handy-Einstellungen neu gelesen.
Nach Ablehnen/bei bestehender Freigabe führt ein Knopf zu den Einstellungen dieser
App; Textchat bleibt ohne Zustimmung möglich. Alle9 App-Sprachen ergänzt.

Clean-Archiv-Build:433 Debug-/401 Release-Tests, beide Node-Verträge grün; Lint0
Fehler/132 Warnungen. Eine zusätzliche Warnung ist nur der UseKtx-Stilhinweis zu
Uri.parse. Keine Abhängigkeit verändert. Signiertes Pilot-APK
`mission-leben-zentral-0.23.4-local-assistant.apk`, SHA256
2008d93af7fa8c89e85085383e349b6d949db7d11aa018fabc9d5b448300b7f6,
um07:39:21 als Update installiert. Für den echten Freigabe-Test wurde nur
RECORD_AUDIO zurückgenommen; der Nutzer muss nun bewusst im neuen Assistenten
zustimmen. Gerätebindung und Anmeldung bleiben erhalten. Geräteprüfung des neuen
Panels noch offen, kein OTA.
