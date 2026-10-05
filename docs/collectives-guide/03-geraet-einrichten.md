Für die Geräte-Einrichtung melden sich IT, Einrichtungsleitung oder PDL auf [geraete.mission-leben.de](https://geraete.mission-leben.de/) an. Die IT kann alle echten Einrichtungen wählen; Leitungen sehen ihre eigenen Einrichtungen. Nutze dafür den Browser innerhalb von Citrix.

## 1. Gerät und Zugang wählen

1. Wähle **Mitarbeiter-Handy** oder **Gemeinsames Tablet**. Nur die IT sieht zusätzlich **Gruppenkonto auf Diensthandy**.
2. Wähle die Person, das Gruppenkonto oder beim Tablet **genau eine** erlaubte Einrichtung. Prüfe die Auswahl vor dem Erstellen.

Bei einem bereits verbundenen persönlichen oder Gruppenkonto-Handy wird das bisherige Gerät erst nach erfolgreicher Registrierung des neuen Handys deaktiviert; die Historie bleibt erhalten.

## 2. Gerät vorbereiten und App installieren

Die folgende Seite bleibt **auf dem PC geöffnet**. Erledige zuerst die dort gezeigten Android- und Samsung-Schritte. „Automatische Sperre“ bei Samsung ist **nicht** die Bildschirmsperre.

**Persönliches Handy oder gebundenes Diensthandy:** Richte vor der Anmeldung eine sichere **Bildschirmsperre mit PIN, Passwort oder Muster** ein und prüfe sie gemeinsam mit der nutzenden Person. Fingerabdruck oder Gesicht sind zusätzlich möglich; ein Gerätecode reicht ebenfalls. Die Bildschirmsperre bleibt auch bei Samsung eingerichtet.

**Ohne nutzbare sichere Bildschirmsperre gibt es keinen gespeicherten Schnellzugang.** Bei jeder neuen App-Sitzung ist dann das Passwort erneut nötig, zum Beispiel nach Bildschirm-Aus oder längerer Pause. Ein kurzer App-Wechsel bei eingeschaltetem Bildschirm ist keine neue Sitzung.

**Gemeinsames Tablet:** Der bisherige Ablauf bleibt erhalten. Nach Bildschirm-Aus ist eine neue Anmeldung nötig; ein persönlicher Schnellzugang wird nicht gespeichert.

1. Scanne den **ersten QR-Code** mit der normalen Handy- oder Tablet-Kamera. Er lädt die App direkt von GitHub. Öffne den Download auf dem Gerät.
2. Erscheint „App scannen“ oder Google Play Protect, lass die Prüfung durchlaufen. Die Prüfung kann bei jeder Installation und jedem Update erneut erscheinen – auch wenn die App vorher problemlos lief. Der Scan allein ist ein normaler Android-Prüfschritt, kein Fehler und kein Hinweis auf einen Angriff. Danach nur „Mission Leben Zentral“ installieren. Warnt Android ausdrücklich vor einer schädlichen App, stimmt der App-Name nicht oder schlägt der Scan fehl, brich ab und frage die IT.
3. Klicke erst danach **am PC** auf **App installiert**.
4. Scanne den nun sichtbaren **zweiten QR-Code**. Er öffnet die installierte App und startet die vorbereitete Registrierung.

Beispiel: Vorbereiten, ersten QR-Code scannen und erst nach der Installation „App installiert“ wählen.

![Schritte 1 und 2: Vorbereitung und App-Download](.attachments.95685145/ml-portal-install-schritte-inline.png)

**Wichtig:** Der Einrichtungslink gilt 30 Minuten **ab Erstellung**; „App installiert“ verlängert ihn nicht. Gib Link oder QR-Code nur an die vorgesehene Person weiter. Ist die Frist abgelaufen, erstelle einen neuen Link.

## 3. Einrichtung prüfen

Öffne Mission Leben Zentral. Prüfe, ob die Geräteidentität bestätigt ist, und öffne mindestens eine freigegebene Anwendung. Bei einem gemeinsamen Tablet wähle zum Test ein Konto der gebundenen Einrichtung und schalte danach den Bildschirm aus.

## Link per E-Mail verschicken

Die IT kann den 30-Minuten-Einrichtungslink für ein persönliches Handy an die in Authentik hinterlegte Adresse der ausgewählten Person senden. Für ein Gruppenkonto-Diensthandy wählt die IT zuerst das Gruppenkonto, sucht dann eine aktive Person als E-Mail-Empfänger und versendet den Link. Die Empfängerperson wird nicht mit dem Handy verbunden; gebunden bleibt ausschließlich das Gruppenkonto. Der Link wird nicht automatisch an die gemeinsame Mailbox geschickt. Die empfangende Person öffnet den Link am PC und folgt den angezeigten Schritten.

Hat eine Person bereits TOTP und will ihr eigenes Handy selbst registrieren, kann die IT stattdessen die [reine Installationsanleitung](https://geraete.mission-leben.de/download) ohne Ablaufdatum verschicken. Dort gibt es keinen zweiten QR-Code: Nach der Installation öffnet die Person die App und meldet sich mit TOTP an. Dafür klickt die IT auf der Installationsseite auf „Person suchen und Installationslink per E-Mail senden“ und wählt die empfangende Person aus.

Duo gilt nur für persönliche Mitarbeiterkonten mit registriertem Handy. Bei späteren Anmeldungen soll die Person auf der Seite mit den Sicherheitsverfahren „Duo“ auswählen, Mission Leben Zentral entsperren und die Push-Anfrage dort bestätigen. Eine nicht selbst ausgelöste Anfrage immer ablehnen. Für gemeinsame Gruppen- und Tablet-Konten ist Duo als Anmeldebestätigung nicht vorgesehen. Die E-Mail-Nutzung auf dem Gruppenkonto-Diensthandy oder Tablet bleibt davon unberührt.

## Bei Verlust oder Ausscheiden

Das Gerät in Authentik deaktivieren und die IT informieren. Nur in der App abmelden reicht nicht

## Nach der Registrierung gemeinsam prüfen

- Beim ersten Anmelden erklärt der Einrichtungsassistent die passenden Einstellungen. Er zeigt nur Schritte, für die das jeweilige Konto freigegeben ist.
- Auf persönlichen Handys: Android-Benachrichtigungen erlauben und unter **Einstellungen → Push-Zustellung** die Akku-Einstellungen prüfen. Sonst können Mail-, Talk- und Terminhinweise ausbleiben.
- Nur bei einem persönlichen Konto mit Zimbra-Freigabe: Der freiwillige Handy-Kalender kann Zimbra-Termine für 1 Tag bis 2 Wochen anzeigen. Termine werden ausschließlich in Zimbra angelegt und geändert; vom Handy geht nichts zurück.
- Auf gemeinsamen Tablets und Gruppenkonto-Diensthandys gibt es keinen persönlichen Handy-Kalenderabgleich. Eine Anmeldung per App-Bestätigung („Duo“) ist für Gruppenkonten ebenfalls nicht vorgesehen.

Die installierte Version steht unten in den App-Einstellungen. Aktuelle reguläre Updates kommen weiterhin über GitHub.
