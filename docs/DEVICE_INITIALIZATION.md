# Geräteinitialisierung

## Vertrauensgrenzen

Die Verwaltungsoberfläche ist eine durch Authentik Forward Auth geschützte Anwendung. Nur der Reverse Proxy darf `X-Authentik-*`-Header zum Container senden. Der Proxy entfernt eingehende Identitätsheader und setzt sie ausschließlich aus der Authentik-Unteranfrage neu.

Die Authentik-Anwendung besitzt einen eigenen Authorization Flow. Auch wenn bereits eine normale Browsersitzung existiert, muss eine berechtigte Leitung ein vorhandenes TOTP oder WebAuthn/Passkey bestätigen. Ohne eingerichteten starken Faktor wird der Zugang abgewiesen; die Geräte-Einrichtung richtet keinen Faktor ein. Der ausgewählte Mitarbeiter selbst benötigt keinen Zugriff auf diese Verwaltungsseite und seine Anmeldedaten werden dort nicht abgefragt.

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
- Keine GF-Sonderrolle und keine Selbstinitialisierung normaler Mitarbeiter.

## Betriebsregeln

- QR-Gültigkeit: fünf Minuten, konfigurierbar nur zwischen zwei und zehn Minuten.
- Genau ein Container-Worker. Vor horizontaler Skalierung ist eine verteilte Einmal-Sperre erforderlich.
- API- und CSRF-Schlüssel ausschließlich als Read-only-Container-Secrets mit Modus `0600`.
- Authentik-Audit muss bereits beim Erzeugen des QR-Codes funktionieren; sonst wird der Token gelöscht und kein QR angezeigt.
- App-Version 0.8.0 oder neuer ist für den automatisch erkannten Gerätemodus und den einmaligen Redeem-Ablauf erforderlich.
