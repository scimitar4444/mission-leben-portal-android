# Benachrichtigungen für Mail, Termine und Talk

Ein WebView kann Hinweise nur zuverlässig darstellen, solange die betreffende Seite geöffnet ist. Hintergrundmeldungen benötigen deshalb einen serverseitigen Ereignisdienst und einen Android-Zustellweg.

## Zielbild

```text
Zimbra Mail/Calendar ─┐
                     ├─ Portal Notification Bridge ─ ntfy ─ Android-App
Nextcloud/Talk ──────┘                              └─ Sperr-/Löschsignal
```

Der vorhandene Device Service kann um diese Bridge erweitert werden; ein weiterer öffentlich erreichbarer Verwaltungsdienst ist nicht erforderlich.

## Ereignisquellen

- **Neue Mail:** Zimbra SOAP WaitSet überwacht Mailboxänderungen. Ein periodischer REST-Abruf aller Postfächer wäre nur die Rückfallebene.
- **Termine:** CalDAV beziehungsweise die Zimbra-Kalender-API liefert Termine und Erinnerungszeiten. Die Bridge plant, aktualisiert und verwirft Erinnerungen einschließlich Serien, Zeitzonen und Absagen.
- **Nextcloud/Talk:** bevorzugt die signierte Nextcloud Notifications Push API. Alternativ veröffentlicht eine schmale serverseitige Nextcloud-Integration relevante Talk-Ereignisse an die Bridge.

## ntfy

Empfohlen ist eine selbst betriebene, nur über TLS erreichbare ntfy-Instanz:

- zufälliges Topic pro Gerät, nicht pro Benutzername
- authentifizierter Publisher; Gerät erhält ausschließlich Leserechte für sein Topic
- kurze Aufbewahrung und keine sensiblen Inhalte im Nachrichtentext
- Deep Links enthalten nur typisierte Ziele wie `open_mail`, `open_calendar` oder `open_talk`; niemals eine frei wählbare URL
- beim Offboarding Topic und Lesetoken sperren, Authentik-/Zimbra-/Nextcloud-Sitzungen widerrufen und ein lokales Löschsignal an registrierte Geräte senden

Für zuverlässige Zustellung bei vollständig geschlossener App gibt es zwei Betriebsarten:

1. **Ohne Google:** selbst gehostetes ntfy mit dauerhafter Verbindung/Foreground Service. Das benötigt eine sichtbare Systemmeldung und etwas Akku.
2. **Mit FCM:** eigene Firebase-Konfiguration für die signierte Portal-App und ntfy. Das ist energiesparender, führt Metadaten aber über Firebase.

Die eigenständige ntfy-App kann zunächst als Pilot verwendet und per MDM vorkonfiguriert werden. Eine spätere direkte Integration in die Portal-App verwendet dasselbe Server- und Berechtigungsmodell.

## Datenschutz

Pushmeldungen enthalten standardmäßig nur minimale Texte, beispielsweise „Neue Mail“, „Termin in 15 Minuten“ oder „Neue Talk-Aktivität“. Betreff, Absender, Teilnehmer und Nachrichteninhalt werden erst nach dem Entsperren aus Zimbra beziehungsweise Nextcloud geladen.

## Offboarding-Grenze

Ein Push-Löschsignal ist keine garantierte Fernlöschung: Ein ausgeschaltetes oder dauerhaft offline befindliches Gerät kann es nicht empfangen. Für dienstliche Daten auf Privatgeräten ist deshalb ein Android Work Profile beziehungsweise MDM-Wipe die belastbare letzte Instanz.
