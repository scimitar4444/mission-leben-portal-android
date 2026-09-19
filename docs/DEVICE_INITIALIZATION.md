# Geräteinitialisierung

## Vertrauensgrenzen

Die Verwaltungsoberfläche ist eine durch Authentik Forward Auth geschützte Anwendung. Nur der Reverse Proxy darf `X-Authentik-*`-Header zum Container senden. Der Proxy entfernt eingehende Identitätsheader und setzt sie ausschließlich aus der Authentik-Unteranfrage neu.

Die Authentik-Anwendung besitzt einen eigenen Authentication Flow aus Benutzername, Passwort und TOTP sowie einen eigenen Authorization Flow mit derselben TOTP-Stufe. Ein kurzlebiger, genau an diese Stufe und das verwendete TOTP-Gerät gebundener Authentik-Cookie verhindert bei einer frischen Anmeldung die doppelte TOTP-Abfrage. Existiert dagegen nur eine ältere normale Authentik-Browsersitzung, bleibt die TOTP-Prüfung zwingend. Ohne eingerichtetes TOTP wird der Zugang abgewiesen; die Geräte-Einrichtung richtet keinen Faktor ein.

## Selbstregistrierung mit TOTP

- Ein aktiver Mitarbeiter wählt in der App „Mit TOTP selbst registrieren“.
- Vor dem Laden löscht die App ihre alten Web-Sitzungen, damit kein vorheriger Benutzer übernommen wird.
- Authentik verlangt Benutzername, Passwort und ein bereits vorhandenes TOTP.
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
- Die Android-App fordert nach erfolgreichem Enrollment die normale App-Anmeldung an; Benutzername und Kennwort allein funktionieren auf einem fremden, nicht registrierten Gerät nicht.

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
- Keine GF-Sonderrolle. Normale Mitarbeiter dürfen ausschließlich ihr eigenes persönliches Gerät per vorhandenem TOTP registrieren.

## Betriebsregeln

- QR-Gültigkeit: fünf Minuten, konfigurierbar nur zwischen zwei und zehn Minuten.
- Genau ein Container-Worker. Vor horizontaler Skalierung ist eine verteilte Einmal-Sperre erforderlich.
- API- und CSRF-Schlüssel ausschließlich als Read-only-Container-Secrets mit Modus `0600`.
- Authentik-Audit muss bereits beim Erzeugen des QR-Codes funktionieren; sonst wird der Token gelöscht und kein QR angezeigt.
- App-Version 0.8.1 oder neuer ist für die TOTP-Selbstregistrierung erforderlich. Der automatisch erkannte QR-Modus und der einmalige Redeem-Ablauf funktionieren ab 0.8.0.
