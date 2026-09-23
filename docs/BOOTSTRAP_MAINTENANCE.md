# Portal- und E2E-Bootstrap sicher pflegen

## Quelle und Zuständigkeit

Die gepflegte Quelle ist dieses Git-Repository:

- `authentik/bootstrap_enrollment_portal.py`
- `authentik/bootstrap_e2e_debug.py`
- Hashfreigabe: `authentik/bootstrap-scripts.json`
- Dateiübertragung: `scripts/sync_authentik_bootstraps.py`

Die Gruppenverwaltung gibt fachlich die Gruppen vor. Einrichter sind ausschließlich
`BR_IT_MANAGEMENT`, `BR_EINRICHTUNGSLEITUNG` und `BR_PFLEGEDIENSTLEITUNG`.
Die Skripte erstellen keine Rollen- oder Ersatzgruppen. Fehlende oder falsch
typisierte erforderliche Gruppen führen vor der ersten Änderung zum Abbruch.
Auch `ORG_E2E_TEST` muss als isolierte Testorganisation bereits vorhanden sein.
Das E2E-Skript vergibt **keine** Einrichterrolle; es erhält höchstens eine schon
separat genehmigte Mitgliedschaft. Alte Mitgliedschaften werden nicht migriert.
Das gilt insbesondere für das bestehende E2E-Konto.

Dateipflege ist weder eine Bootstrap-Ausführung noch eine Freigabe für Änderungen
an Authentik. Flows/MFA bleiben fachlich beim Flow-Verantwortlichen. Es gibt
keinen periodischen Abgleich und keine automatische Ausführung nach Updates.

## Änderungen vorbereiten

1. Nur die gepflegten Repository-Dateien ändern; keine Serverkopie separat pflegen.
2. Änderungen gegen die zuständigen Gruppen-/Flow-Verträge prüfen.
3. Offline-Regressionen ausführen:

   ```sh
   python3 -m unittest discover -s authentik/tests -p 'test_bootstrap_*.py' -v
   ```

4. Nach der inhaltlichen Prüfung neue SHA-256-Werte mit `sha256sum` ermitteln und
   im Manifest ausdrücklich freigeben; Tests erneut ausführen und committen.
   CI prüft Hashes, Legacy-Ausschluss, Ausführungs-/Versionsschutz, fehlende Gruppen,
   keine automatische Rollenvergabe sowie den reinen Dateitransfer und Rückweg.
5. Vor einem Authentik-Upgrade die Kompatibilität prüfen. Beide Bootstraps sind
   derzeit ausdrücklich auf **2026.8.3** begrenzt. Eine andere Version darf nicht
   durch ein automatisches Lockern der Prüfung freigegeben werden. Modelle,
   Felder, Enums, Berechtigungen und Flow-Semantik müssen zuerst in einer isolierten
   Umgebung geprüft werden; dann Versionsschutz und Manifest gemeinsam ändern.

## Server prüfen und genau zwei Dateien übertragen

Der Standardaufruf ist rein lesend. Bei Abweichung liefert er Exitcode 2:

```sh
python3 scripts/sync_authentik_bootstraps.py --host authentik-id
```

Er kontrolliert den aktuellen Git-Stand, die expliziten Manifest-Hashes und die
Serverdateien. Uncommittete Änderungen an diesen Quellen oder am Übertragungswerkzeug
werden abgelehnt. Andere Arbeitskopieänderungen sind kein Teil der Übertragung.
Die SSH-Verbindung setzt ein bereits vertrautes Hostprofil voraus.

Erst nach Freigabe der zwei konkreten Server-Hashes:

```sh
python3 scripts/sync_authentik_bootstraps.py --host authentik-id --apply \
  --expect-portal-sha256 <zuvor-geprüfter-Serverhash> \
  --expect-e2e-sha256 <zuvor-geprüfter-Serverhash>
```

Die Ziele sind fest auf diese beiden Dateien begrenzt:

```text
/opt/authentik/admin-changes/bootstrap_enrollment_portal.py
/opt/authentik/admin-changes/bootstrap_e2e_debug.py
```

Vorher entsteht eine private Sicherung unter
`/opt/authentik/backups/bootstrap-source-sync/<UTC-Zeit>-<Zufall>/`.
Sie enthält beide Originale sowie `verification.json` mit Vorher-/Nachher-Hashes,
UID/GID, Modus, Zeitstempel, Manifest und Quellcommit. Die Übertragung erhält
Eigentümer und Zugriffsrechte, prüft Syntax ohne Ausführung und verweigert
unerwartete Hashes, Symlinks, Hardlinks und konkurrierende Änderungen. Anschließend
werden beide Dateien erneut geprüft. Ein erneuter identischer Auftrag ist ein No-op.

**Die Skripte werden dabei niemals ausgeführt.** Kein `ak shell`, kein Datenbank-
Zugriff, kein Containerneustart und kein Deployment von Portal, Bridge oder App.
Eine anschließende Prüfung ohne `--apply` muss `verified` melden.

## Getrennte spätere Bootstrap-Ausführung

Nur mit gesondertem Auftrag, geeigneter Datenbanksicherung und inhaltlicher
Gruppen-/Flow-Freigabe. Beide Skripte brechen ohne
`ML_AUTHENTIK_BOOTSTRAP_APPLY=1` und bei einer ungeprüften Authentik-Version ab.
Diese Variable ist eine Absichtserklärung, keine technische Benutzerberechtigung.
Sie wird vom Übertragungswerkzeug weder gesetzt noch in einen Dienst geschrieben.
Beim Portal-Bootstrap muss die einzige geheime Ausgabe weiterhin unmittelbar in
eine root-only Token-Datei umgeleitet werden; niemals ins Terminal oder CI-Log.

Das E2E-Skript ist kein Reparatur- oder Gruppenmigrationswerkzeug. Unerwartete
vorhandene Mitgliedschaften führen weiter zum Abbruch. Ein Testkonto ohne separat
erteilte Rolle erhält keine neue Einrichterberechtigung. Vorhandene MFA-, Redirect-
und Tokenfunktionen bleiben erhalten, sind aber nicht durch Dateipflege autorisiert.

## Rückweg und Grenzen

Die Sicherung nicht vor Ende der vereinbarten kurzen Rückhaltefrist entfernen.
Vor einer manuellen Wiederherstellung Hashes und Metadaten aus verification.json
kontrollieren; nur die zwei bezeichneten Dateien wiederherstellen. Die Sicherung
vom 23.09.2026 enthält noch Legacy-Bootstraps und darf deshalb nicht ungeprüft
wieder als ausführbarer Betriebsstand genutzt werden. Ein Code-Rollback ist kein
Rollback von Gruppen oder Flows und setzt keine gelöschten Gruppen wieder ein.

Jede einzelne Datei wird atomar ersetzt, nicht beide als gemeinsame Transaktion.
Bei einem abgefangenen Fehler wird ein bereits ersetztes, nicht fremd verändertes
Ziel aus der Sicherung zurückkopiert. Strom-/Verbindungsabbruch oder gleichzeitige
manuelle Administration erfordern anschließend eine erneute Hashprüfung. Während
der kurzen Übertragung keine manuelle Bootstrap-Ausführung starten.

Dies verhindert Drift über den beschriebenen Übertragungsweg. Es verhindert nicht,
dass root manuell alte Dateien kopiert, ältere Git-Stände verwendet oder Proxmox/
Datenbank-Sicherungen wiederherstellt. Nach einer solchen Wiederherstellung zuerst
die aktuelle Quelle und beide Server-Hashes vergleichen. Historische `.before-*`-
Dateien sind keine gepflegte Quelle. Die gezielte Host-Prüfung fand zum Zeitpunkt
der Umstellung keine direkten systemd-/cron-Aufrufe dieser zwei Bootstraps; unbekannte
externe Aufrufe kann sie nicht ausschließen. Absolute Updatefestigkeit wird nicht
zugesichert. Für eine spätere produktive Ausführung bleiben isolierte Integrationstests
und die getrennte fachliche Freigabe erforderlich.
