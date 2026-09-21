# Geräteinitialisierung

## Vertrauensgrenzen

Die Verwaltungsoberfläche ist eine durch Authentik Forward Auth geschützte Anwendung. Nur der Reverse Proxy darf `X-Authentik-*`-Header zum Container senden. Der Proxy entfernt eingehende Identitätsheader und setzt sie ausschließlich aus der Authentik-Unteranfrage neu.

Die Authentik-Anwendung besitzt einen eigenen Authentication Flow aus Benutzername, Passwort und vorhandenem TOTP, Passkey oder registrierter App-Bestätigung sowie einen eigenen Authorization Flow mit derselben Faktor-Stufe. Ein kurzlebiger, genau an diese Stufe und das verwendete Authentikator-Gerät gebundener Authentik-Cookie verhindert bei einer frischen Anmeldung die doppelte Abfrage. Existiert dagegen nur eine ältere normale Authentik-Browsersitzung, bleibt die Faktorprüfung zwingend. Ohne eingerichteten starken Faktor wird der Zugang abgewiesen; die Geräte-Einrichtung richtet keinen Faktor ein. Für das allererste persönliche Gerät bleibt TOTP oder Passkey erforderlich.

## Selbstregistrierung mit vorhandenem starken Faktor

- Ein aktiver Mitarbeiter öffnet in der App die persönliche Registrierung.
- Vor dem Laden löscht die App ihre alten Web-Sitzungen, damit kein vorheriger Benutzer übernommen wird.
- Authentik verlangt Benutzername, Passwort und ein bereits vorhandenes TOTP, einen Passkey oder die Bestätigung in einem schon registrierten persönlichen Gerät.
- Der Container ermittelt den Mitarbeiter ausschließlich aus den vom Authentik-Proxy gesetzten Identitätsheadern. Es gibt weder Benutzerauswahl noch Shared-Modus.
- Die persönliche Device Access Group wird vor Ausgabe des Einmal-Links direkt an genau dieses Konto gebunden.
- Nach der Bestätigung leitet der Container über den vollständigen Enrollment-Deep-Link zur App zurück. Die App registriert das Gerät, speichert den Device Token und startet automatisch die normale App-Anmeldung in derselben WebView-Sitzung.

Damit reicht ein TOTP nicht als frei übertragbarer Enrollment-Code: Benutzeridentität, kurzlebiger Authentik-Enrollment-Token und der neu erzeugte Geräteschlüssel werden in einem Ablauf zusammengeführt.

Der einzige unangemeldet erreichbare Schreibendpunkt ist:

```http
POST /api/v1/enrollments/{authentik-token-uuid}/redeem
Authorization: Bearer <kurzlebiger Authentik-Enrollment-Token>
```

Der Container verifiziert UUID, Tokenwert, Connector, Ablaufzeit, Gerätemodus und Device Access Group. Danach ruft er Authentiks Agent-Enrollment auf und löscht den Enrollment-Token, bevor er den neuen Device Token an die Android-App zurückgibt. Device Token oder Enrollment-Token werden nicht gespeichert oder protokolliert.

## Persönliches Gerät

- Eine berechtigte Person wählt einen aktiven Mitarbeiter innerhalb ihres `ORG_*`-Bereichs.
- Der Container legt eine persönliche Device Access Group pro Benutzer-UUID an.
- Diese Gruppe besitzt genau eine primäre Benutzerbindung. Unerwartete zusätzliche Bindungen führen zu einem harten Abbruch.
- Der QR-Code bindet den Endpoint bereits vor der ersten Kennwortprüfung an diesen Benutzer.
- Derselbe verifizierte HTTPS-App-Link öffnet eine vorhandene App direkt oder führt auf einem neuen Gerät durch Installation und anschließende Einrichtung. Der Einmal-Token bleibt im URL-Fragment und erreicht den Webserver nicht.
- Die Android-App fordert nach erfolgreichem Enrollment die normale App-Anmeldung an; Benutzername und Kennwort allein funktionieren auf einem fremden, nicht registrierten Gerät nicht.
- Pro Mitarbeiter bleibt genau ein persönliches Handy aktiv. Vorhandene aktive Geräte werden in der Auswahl mit Name und letzter Meldung angezeigt.
- Ein vorhandenes Gerät bleibt während der QR-Ausgabe aktiv. Erst nachdem Authentik den neuen Endpoint erfolgreich registriert und dessen Geräteschlüssel bestätigt hat, markiert der Container alle vorherigen persönlichen Geräte dieser Benutzerbindung als deaktiviert.
- Der alte Endpoint wird weder gelöscht noch mit einem sofortigen Ablaufdatum versehen. Die Attribute `mission-leben.de/status=disabled`, Deaktivierungszeitpunkt und Grund bleiben für Audit und eine kontrollierte Wiederherstellung in Authentik sichtbar; die Authentik-Policies verweigern ihm jede Endpoint-Prüfung und App-Bestätigung.
- Schlägt das Sperren des alten Geräts fehl, wird das neue Gerät vorsorglich ebenfalls dauerhaft als deaktiviert markiert und der Vorgang mit einem Fehler beendet. Ein Shared Tablet wird niemals durch diese Regel ersetzt.

## Shared Tablet

- Eine berechtigte Person wählt eine ihrer Einrichtungen und einen verständlichen Gerätenamen.
- Der Container verwendet die standortbezogene Device Access Group `Mission Leben Android - Shared - ORG_*`.
- Die Gruppe besitzt genau eine Bindung an die zugehörige Organisationseinheit.
- Einzelne Mitarbeitersitzungen werden weiterhin nicht dauerhaft auf dem Tablet gespeichert.

## Bedienrollen

- IT: globaler Bereich und Support.
- Leitungen in der Zentrale: ausdrücklich zugewiesene `ORG_*`-Bereiche.
- EL: eigene Einrichtung.
- PDL: eigene Einrichtung.
- Keine GF-Sonderrolle. Normale Mitarbeiter dürfen ausschließlich ihr eigenes persönliches Gerät mit einem vorhandenen starken Faktor registrieren; beim ersten Gerät ist das TOTP oder Passkey.

## Betriebsregeln

- QR-Gültigkeit: zehn Minuten, konfigurierbar nur zwischen zwei und zehn Minuten.
- Genau ein Container-Worker. Vor horizontaler Skalierung ist eine verteilte Einmal-Sperre erforderlich.
- API- und CSRF-Schlüssel ausschließlich als Read-only-Container-Secrets mit Modus `0600`.
- Authentik-Audit muss bereits beim Erzeugen des QR-Codes funktionieren; sonst wird der Token gelöscht und kein QR angezeigt.
- App-Version 0.10.0 oder neuer ist für den verifizierten HTTPS-App-Link erforderlich. Die Selbstregistrierung funktioniert ab 0.8.1; Passkeys sind im Portal-Flow ab der hier dokumentierten Konfiguration zugelassen.
- Das Portal-Servicekonto benötigt `view_device` und `change_device`, aber ausdrücklich kein `delete_device`, damit es vorhandene persönliche Endpoints anzeigen und nach einem erfolgreichen Austausch nachvollziehbar deaktivieren kann.
