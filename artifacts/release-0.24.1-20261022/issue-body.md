## Problem und Ziel

Auf einem korrekt registrierten Gerät führten E-Mail-Adresse bzw. ein führendes @ statt des Authentik-Anmeldenamens zur missverständlichen Meldung „Gerät oder Benutzer nicht freigegeben“. Die App soll Eingaben verständlich erklären, sichtbare Korrekturen nur nach Nutzerentscheidung anbieten und unverändert Authentik über Anmeldung und Freigabe entscheiden lassen.

## Sicherheitsgrenzen

Nur die Identifikationsstufe der beiden Android-Flows am exakten HTTPS-Origin. Kein Lesen von Passwort/TOTP, keine Benutzerabfrage oder automatische Anmeldung, keine Änderungen an Endpoint Trust, MFA, Flows, Gruppen oder Backend. Unbekannte einfache Namen und numerische Kennungen werden nicht als ungültige Identitäten geraten. Serverablehnungen bleiben verbindlich; ihre Originalmeldung bleibt zugänglich.

## Abnahme und Release

Auf einem produktiv signierten lokalen Pilot nach menschlicher Biometrie wurden Hinweise, explizite Korrektur und No-Submit-Schutz im realen Android-WebView geprüft. Keine echte Anmeldung mit Testdaten und kein Live-Sperrtest durchgeführt. Nutzer hat danach ausdrücklich das OTA beauftragt. Ziel 0.24.1/102 aktualisiert öffentliche100 und Pilot101 ohne Datenlöschung.
