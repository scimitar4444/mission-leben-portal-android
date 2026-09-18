# Benachrichtigungen für Mail, Termine und Talk

Ein WebView kann im Hintergrund keine zuverlässigen Hinweise erzeugen. Deshalb besteht die Lösung aus Quelladaptern, Bridge, FCM und einem signierenden Android-Gerät.

```text
Zimbra WaitSet/Calendar ─┐
                        ├─ Notification Bridge ─ FCM (nur Ereignis-ID) ─ Android
Nextcloud/Talk Adapter ──┘                                      │
                                                               └─ signierter Detailabruf
```

## Was bereits implementiert ist

Android-App:

- getrennte Kanäle für Mail, Termine, Talk und Gerätesicherheit
- Data Messages mit fester Aktionsliste; freie URLs und FCM-Texte werden ignoriert
- sofortiger neutraler Hinweis und nachgelagerter Detailabruf über einen Android-Job
- Detailabruf mit Geräte-ID, Schlüssel-ID, Zeitstempel, Nonce und P-256-Signatur
- Sperrbildschirm-Version ohne Absender, Betreff, Termin oder Vorschau
- `Diskret`, `Standard` und `Ausführlich` auf persönlichen Geräten
- Shared Tablets erzwingen `Diskret`
- beim Antippen öffnet nur die passende, durch Authentik freigegebene Web-App
- Abmeldung, Profilwechsel und Moduswechsel entfernen Hinweise und ausstehende Detailjobs

Kommunikations-Bridge:

- Live-Prüfung von Authentik-Benutzer und Authentik-Device-Token; keine eigene Gerätefreigabe
- verschlüsselte FCM-Installations-IDs
- FCM HTTP v1 mit Ereignis-ID statt Inhalt
- Detailfreigabe nur an eine registrierte Kommunikationsidentität, deren Authentik-Device-Token weiterhin gültig ist
- zeitgesteuerte Kalenderhinweise
- Zimbra WaitSet für Mail-/Kalenderänderungen ausgewählter Konten
- Abruf von Mail-Absender, Betreff und optionalem Fragment
- Abruf von Kalenderinstanz, Betreff, Uhrzeit und Ort
- nativer Talk-Bot-Webhook mit offizieller Talk-HMAC-Prüfung sowie ein allgemeiner HMAC-Vertrag für weitere Adapter

## Anzeige je Datenschutzstufe

| Gerät/Stufe | Mail | Termin | Talk |
|---|---|---|---|
| Shared oder `Diskret` | „Neue Mail – Zimbra öffnen“ | „Termin – Ein Termin steht bevor“ | „Talk – Neue Talk-Aktivität“ |
| `Standard` | Absender + Betreff | Betreff + Zeit/Ort | Absender + Raum |
| `Ausführlich` | zusätzlich kurze Vorschau | wie Standard | zusätzlich kurze Vorschau |

Die Tabellenfelder sind ein Darstellungsvertrag. Der jeweilige Quelladapter muss sie korrekt normalisieren; freie Ziel-URLs bleiben verboten.

## Warum nicht alle Details direkt über FCM gehen

FCM-Verbindungen sind transportverschlüsselt, aber Data Messages sind nicht automatisch Ende-zu-Ende-verschlüsselt. Deshalb transportiert FCM hier nur eine wertlose, kurz verwendbare Ereignis-ID. Details liefert die eigene Bridge erst nach Geräteprüfung aus.

## Zimbra-Verbindung

Ein Worker hält einen blockierenden WaitSet-Kanal pro Zimbra-Mailbox-Server. Er besitzt keine Benutzerkennwörter und hält keine Benutzersitzung. Zimbra verlangt für einen WaitSet über mehrere Konten jedoch ein Admin-Token. Der Worker muss daher mit einem eigenen Integrationskonto und einer expliziten Liste der überwachten Konto-IDs betrieben werden.

Der WaitSet ist nur ein Änderungssignal. Nach einem Signal führt der Worker eine begrenzte Suche im betroffenen Konto durch, dedupliziert über Zimbra-Konto- und Objekt-ID und sendet das normalisierte Ereignis an die Bridge. Für Kalenderinstanzen plant die Bridge den Hinweis bis zum Erinnerungszeitpunkt.

## Nextcloud/Talk

Nextcloud liefert Talk-Ereignisse nicht über den Zimbra-Worker. Die Bridge stellt deshalb `/sources/nextcloud-talk` als Talk-Bot-Webhook bereit. Sie prüft die offizielle Talk-Signatur über Zufallswert plus unveränderten Request-Body, den erwarteten Nextcloud-Backend-Header und Wiederholungen. Eine Konfiguration ordnet Raumtoken ihren Empfänger-Subjects und Nextcloud-Benutzer den stabilen Authentik-Subjects zu. Der Bot muss in den gewünschten Räumen aktiviert und anschließend gegen die installierte Talk-Version getestet werden.

## Offboarding

Bei Kontosperre wird die Push-Zuordnung serverseitig deaktiviert. Wenn ein Authentik-Gerät abläuft, gelöscht wird oder sein Connector deaktiviert ist, schlägt die Live-Prüfung fehl und die Bridge entfernt dessen Push-Zuordnung. Die App leert bei erkanntem Sperrstatus oder sicherer Abmeldung WebView-Cookies, Webspeicher, HTTP-Zugangsdaten, Cache, Downloads und lokale Hinweise.

Ein garantiertes Fernlöschen auf einem dauerhaft ausgeschalteten Gerät ist technisch nicht durch Push erreichbar. Dafür bleibt MDM beziehungsweise Android Work Profile erforderlich.
