# OTA 0.23.3 – erneut freigegeben, Vorbereitung

Der Nutzer hat die Veröffentlichung vor Merge/Tag/Release zunächst mit „Warte“
angehalten und nach der Textkorrektur mit „Okay. Dann ota“ erneut freigegeben.
Zum Vorbereitungszeitpunkt bleibt öffentlich0.23.2; noch kein Merge/Tag/Release.

Danach wurde ausschließlich der Erklärungstext auf der Archivseite gekürzt,
in allen9Sprachen. Deutsch: „Hier findest du neue und gelesene Meldungen der
letzten14Tage.“ Kein Nextcloud-Bezug und keine Änderung an Fristen oder Backend.
Dokument-/Ticketmeldungen hatten bereits eine14-Tage-Kennungshistorie. Das finale
Release bekommt versionCode98 bei weiter versionName0.23.3, damit auch das
installierte Pilot-Build97 das Update angeboten bekommt.

Die hier vor dem Stopp vorbereiteten Dateien `mission-leben-zentral.apk` und
`update.json` enthalten noch den alten langen Text. NICHT veröffentlichen oder
als aktuelles Build behandeln. Sie stimmen mit dem früher physisch geprüften
Pilot-APK überein (SHA56a144861173d199e2f549dc6cb56686988b08cb616a178cc4b5e1cab73aa7a4).
Nach neuer Freigabe aus dem endgültigen Commit neu bauen, signieren und
Manifest/Hash/Signatur/Payload/CI erneut prüfen. Das zuletzt geprüfte Handy ist
derzeit nicht per USB da; die Funktionsabnahme stammt vom Pilot97, die rein
sprachliche Schlusskorrektur wird synthetisch geprüft und klar getrennt dokumentiert.
