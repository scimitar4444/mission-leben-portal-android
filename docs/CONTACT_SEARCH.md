# Kontaktsuche

Das Suchfeld findet Vorname, Nachname, Anzeigename, geschäftliche E-Mail,
Einrichtung und Funktion. Groß-/Kleinschreibung und Akzente werden ignoriert;
z. B. findet `Bjorn` auch `Björn`. Mehrere Suchwörter müssen alle passen, ihre
Reihenfolge ist beliebig. Teilnamen sind möglich.

Ab Bridge 0.10.3 exportiert die read-only Authentik-Projektion zusätzlich die
expliziten Personenattribute `givenName` und `sn` als interne `search_names`.
Dadurch ist ein Vorname auch auffindbar, wenn der vorhandene Anzeigename ihn
nicht enthält. Es werden keine Namen aus Kontokürzeln, Mailadressen oder freien
Attributen erraten. Anzeigenamen und Quellkonten bleiben unverändert.

Die Suchnamen bleiben im kurzlebigen, zugriffsgeschützten Serverindex. Die
Kontakt-API gibt weiter ausschließlich ihre bisherigen Anzeigefelder zurück.
Alte Projektionen ohne dieses optionale Feld bleiben kompatibel. Hausfilter,
Nutzer-/Gerätebindung und Zugangsregeln gelten unverändert. Der Alphabetfilter
bezieht sich weiter auf den angezeigten Namen; die Eingabe eines Suchtexts setzt
einen vorher gewählten Buchstaben zurück.

Die Android-Suche benennt Vor- und Nachnamen ausdrücklich. Dafür ist keine neue
Kontaktberechtigung und kein Zugriff auf das persönliche Telefonadressbuch nötig.
