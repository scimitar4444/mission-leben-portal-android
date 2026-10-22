# Glocke und Talk-Sprachnachrichten – Anleitung

Am 22.10.2026 auf Nutzerauftrag live ergänzt und im angemeldeten Browser geprüft.

- Android-App / 95685099: Glocke → Neu für mich, 14 Tage, Lesen/Ausblenden,
  Ablauf und Löschen einer bereits empfangenen Ankündigung, kein Datei-/Mail-/Chatarchiv.
- Persönliches Handy / 95685102: dieselbe Regel kurz erklärt und auf die Kapitel verlinkt.
- Talk: freiwillige Mikrofonfreigabe im Assistenten oder Einstellungen → Talk;
  Mikrofon startet die Aufnahme, Haken beendet und sendet sofort, X verwirft;
  Verlassen/Bildschirm-Aus verwirft nur ungesendete Aufnahmen, Text bleibt ohne Freigabe möglich.
- Die vorhandenen 30 Anhänge, vier anderen Seiten, IDs und Berechtigungen blieben unverändert.
- Keine neue APK und kein weiteres OTA durch die Dokumentationsänderung.

`before.json`, `plan.json`, `after.json` und `evidence.json` enthalten den eng
abgegrenzten Read-derived-Stand ohne Anmeldedaten. `glocke-browser.png` und
`talk-browser.png` belegen die gerenderte Hilfe. Die beiden Links in der
persönlichen Anleitung wurden gegen die tatsächlich erzeugten Kapitelanker geprüft.

## Nextcloud-35-Kompatibilität

Auf ml-nextcloud / 172.16.40.140 laufen Nextcloud 35.0.2, Text 9.0.1 und
Collectives 4.7.1. Die geänderte Text-API wurde direkt im installierten
`apps/text/lib/Service/DocumentService.php`, `Db/DocumentMapper.php`,
`Db/StepMapper.php` und `Context/FileContext.php` gelesen.
Der projektspezifische Helfer `docs/collectives-guide/update_app_guides.php`
verwendet jetzt explizit `load('file', fileId)` und
`resetDocument('file', fileId)`; die Prüfung ungespeicherter Schritte entspricht
der installierten Reset-Implementierung. Unbekannte Signaturen brechen ab.
Der Legacy-Zweig wurde nicht erneut auf einer Altinstanz ausgeführt.
Es wurde kein Nextcloud-Core- oder App-Code gepatcht.

PHP-Lint, Snapshot, Preflight, Save und vollständiger Readback erfolgreich.
Beide betroffenen Text-Sitzungen wurden ohne `force` zurückgesetzt. Nach der
Browserprüfung passen alle sechs Seiteninhalte weiterhin zum Plan, und keine
Seite hat ungespeicherte Text-Änderungen. Beim Öffnen der persönlichen Anleitung
entstand kurz ein Text-Initialisierungsschritt; er wurde regulär gespeichert,
ohne dass die Anleitung oder ihr Hash abwich. Es wurde nichts erzwungen.

## Rückweg

Nur bei Bedarf und nach erneutem Lesen des aktuellen Standes:

```sh
sudo -u www-data php /tmp/ml-app-guides.TfoGETHx/update_app_guides.php /tmp/ml-app-guides.TfoGETHx/plan.json --rollback
```

Der Helfer verlangt die bekannten Nachher-Hashes, unveränderte Anhänge und
keine ungespeicherten Text-Änderungen. Nach einer zwischenzeitlichen
Redaktion nicht erzwingen. Der lokale Plan ermöglicht das Wiederbereitstellen
des exakten kurzfristigen Rückwegs; kein neues dauerhaftes Server-Backup nötig.
Eine schon geöffnete alte Hilfeansicht muss neu geladen werden.
