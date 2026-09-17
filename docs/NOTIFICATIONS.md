# Benachrichtigungen für Mail, Termine und Talk

Ein WebView kann Hinweise nur zuverlässig darstellen, solange die betreffende Seite geöffnet ist. Hintergrundmeldungen benötigen deshalb eine serverseitige Ereignisbrücke. Die Android-App verwendet dafür direkt Firebase Cloud Messaging (FCM); ntfy ist kein notwendiger Zustell-Zwischenschritt.

## Zielbild

```text
Zimbra Mail/Calendar ─┐
                     ├─ Portal Notification Bridge / Device Service ─ FCM ─ Android-App
Nextcloud/Talk ──────┘
```

Der vorhandene Device Service kann um diese Bridge erweitert werden. Die FCM-Dienstkonto-Zugangsdaten bleiben ausschließlich dort. Die App registriert nach Gerätefreigabe und Benutzeranmeldung ihre Firebase-Installations-ID über die authentifizierte Device-Service-API.

## Bereits in der Android-App umgesetzt

- vier getrennte Android-Kanäle: Mail, Termine, Talk und Gerätesicherheit
- Laufzeitfreigabe für Benachrichtigungen ab Android 13
- FCM-Registrierung nur bei vollständiger Build-Konfiguration und erteilter Android-Freigabe
- Zuordnung der Installations-ID zu einem freigegebenen Gerät über `PUT /v1/push/registrations/{device_id}`
- Entfernen dieser Zuordnung bei sicherer Abmeldung oder Profil-Reset
- ausschließlich Data Messages mit einer typisierten Aktion
- feste Texte aus dem App-Code; Nachrichtentext aus FCM wird ignoriert
- beim Antippen wird nur eine passende, durch Authentik gelieferte Web-App geöffnet

Zulässige Aktionen:

| Aktion | Lokaler Hinweis | Ziel |
|---|---|---|
| `open_mail` | „Neue Mail – Zimbra öffnen“ | freigegebene Zimbra-App |
| `open_calendar` | „Termin – Ein Termin steht bevor.“ | freigegebene Zimbra-App |
| `open_talk` | „Talk – Neue Talk-Aktivität.“ | freigegebene Talk-/Nextcloud-App |
| `refresh_security_state` | neutraler Sicherheitshinweis | Portal-Startseite |

Unbekannte Aktionen, frei angegebene URLs sowie vom Server gelieferte Titel oder Texte werden verworfen.

## Noch serverseitig umzusetzen

- **Neue Mail:** Zimbra SOAP WaitSet überwacht Mailboxänderungen. Ein periodischer Abruf ist nur Rückfallebene.
- **Termine:** CalDAV beziehungsweise Zimbra-Kalender-API liefert Termine und Erinnerungszeiten. Die Bridge plant, aktualisiert und verwirft Erinnerungen einschließlich Serien, Zeitzonen und Absagen.
- **Nextcloud/Talk:** bevorzugt die signierte Nextcloud Notifications Push API. Alternativ veröffentlicht eine schmale Nextcloud-Integration relevante Talk-Ereignisse an die Bridge.
- **Versand:** Device Service prüft vor jedem Push aktives Konto, Gerätefreigabe und erlaubten Nachrichtentyp und sendet anschließend über die FCM HTTP-v1-API.

## Rolle von ntfy

Eine selbst betriebene ntfy-Instanz kann zusätzlich als interner Ereignis-Bus, Monitoring-Ziel oder Pilot dienen. Für die Android-Zustellung ist sie in dieser Architektur nicht erforderlich. Ein Betrieb ganz ohne Google wäre möglich, bräuchte aber in der App eine dauerhafte Verbindung beziehungsweise einen sichtbaren Foreground Service und hätte höheren Akkuverbrauch.

## Datenschutz und Offboarding

Pushmeldungen enthalten keine Absender, Betreffzeilen, Teilnehmer oder Gesprächsinhalte. Diese Daten lädt die App erst nach Anmeldung aus Zimbra oder Nextcloud.

Bei sicherer Abmeldung versucht die App, die Benutzer-Geräte-Zuordnung am Device Service zu entfernen. Beim Offboarding muss der Server zusätzlich Konto und Gerätebindung sperren und darf keine weiteren FCM-Nachrichten versenden. Ein Push-Löschsignal allein ist keine garantierte Fernlöschung: Ein ausgeschaltetes oder dauerhaft offline befindliches Gerät kann es nicht empfangen. Für einen belastbaren Wipe dienstlicher Daten bleibt Android Work Profile beziehungsweise MDM erforderlich.
