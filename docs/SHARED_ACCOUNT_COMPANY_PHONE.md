# Gruppenkonto auf einem Diensthandy

Stand: 24.09.2026. Lokal implementiert und mit automatisierten Tests geprüft;
**noch nicht produktiv ausgerollt oder mit einem echten Diensthandy abgenommen**.

## Fachliche Regel

Ein bestehendes, aktives und für interaktive Anmeldung freigegebenes
`shared/mailbox`-Konto darf direkt auf einem dienstlichen Android-Handy
angemeldet werden. Nur ein aktives Mitglied von `BR_IT_MANAGEMENT` darf dafür
im Geräteportal einen QR-Code ausstellen. Die IT bestätigt dabei, dass es ein
Firmengerät ist. Das Konto wird nicht zusätzlich einer natürlichen Person
zugeordnet. TOTP-Selbstregistrierung und App-Anmeldebestätigungen für dieses
Konto sind ausgeschlossen. Die Einrichtung benötigt kein internes WLAN.

Das Konto kann organisatorisch für Tablet **oder** Diensthandy vorgesehen
werden. Der Betrieb entscheidet und dokumentiert dies. Eine technisch atomare
Exklusivität zwischen beiden Nutzungsarten ist **nicht** vorhanden und wird
nicht vorgetäuscht. Beim Wechsel muss die IT die frühere Nutzung und noch
aktive Sitzungen prüfen. Es wird keine Authentik-Core-Anpassung, keine neue
Verzeichnisgruppe und kein zweiter OIDC-Client eingeführt.

## Technischer Weg

1. Das Geräteportal filtert aktive interaktive `shared/mailbox`-Konten und
   stellt die Auswahl ausschließlich der IT bereit. Der QR-Code gilt 30
   Minuten und wird beim Einlösen verbraucht.
2. Eine persönliche DeviceAccessGroup wird direkt an genau das Zielkonto
   gebunden. `mission-leben.de/handset-profile=shared-account` und
   `mission-leben.de/device-ownership=company` unterscheiden sie vom
   Mitarbeiterhandy. Die Marker werden auch auf dem registrierten Gerät
   gespeichert.
3. Das Geräteportal akzeptiert den speziellen QR-Code nur von einer App, die
   das Profil ausdrücklich unterstützt. Ein weiteres aktives Handy desselben
   Kontos wird beim Ersatz deaktiviert, nicht gelöscht. Der bestehende
   Gerätewechsel ist jedoch keine kontoübergreifende Authentik-Transaktion.
4. Die Android-spezifischen Authentik-Authentication- und
   Authorization-Policies erlauben das Konto nur mit übereinstimmenden
   Gruppen-, Geräte-, Eigentums- und Benutzermerkmalen. Die vorhandene
   Endpoint-Prüfung bleibt erforderlich. Der TOTP-only-Zweig gilt weiterhin
   ausschließlich für persönliche `person/person`-Konten; für das
   Gruppenkonto bleibt die Passwortanmeldung.
5. Die App speichert das vom Portal bestätigte Profil und richtet keine
   Anmeldebestätigung für das Gruppenkonto ein. Die Bridge weist eine
   entsprechende Registrierung zusätzlich serverseitig ab.

Die bisherigen persönlichen Mitarbeitergeräte und hausgebundenen Tablets
behalten ihre eigenen Regeln. „Mobil erreichbar“ ist kein Berechtigungsmerkmal.

## Betriebsgrenzen

- Eine Gerätesperre verhindert neue gerätegebundene Authentik-Anmeldungen.
  Bereits ausgestellte Access-/Refresh-Tokens und Web-Sitzungen müssen beim
  Widerruf gesondert betrachtet werden; die Gerätesperre ist kein
  nachgewiesener Sofort-Wipe aller Sitzungen.
- Ein ausscheidender einzelner Mitarbeiter sperrt dieses gemeinsam genutzte
  Konto nicht automatisch. IT sperrt das Diensthandy oder deaktiviert das
  Gruppenkonto bei Verlust, Umwidmung oder Auflösung des Kontos.
- Vor produktiver Freigabe sind der reale IT-QR-Weg, Android-Anmeldung,
  Geräteersatz, Sperre und der Tablet-/Handy-Wechsel mit einem Testkonto
  nachzuweisen. Die Authentik-Flow-Vorgabe prüft die konkreten
  Policy-Änderungen vor dem Ausrollen.

Fachliche Quelle: `Android-Shared-Konto-Diensthandy-Vertrag-v1-2026-09-24.json`
im Task „Authentik Gruppen Vorgabe“; Stand der Flow-Abstimmung:
`Mission-Leben-Zentral-Shared-Konto-Diensthandy-Betriebsentscheidung-ohne-Core-Patch-2026-09-24.md`
im Task „Authentik Flow Vorgabe“.
