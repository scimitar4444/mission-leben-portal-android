# Firebase Cloud Messaging einrichten

FCM ist optional. Ohne Firebase-Konfiguration startet und baut die App unverändert; Push bleibt deaktiviert. Auch bei konfiguriertem Build registriert sich die App erst nach Anmeldung und ausdrücklicher Android-Benachrichtigungsfreigabe bei FCM.

## 1. Android-App in Firebase anlegen

Im eigenen Firebase-Projekt eine Android-App mit dem Paketnamen `de.missionleben.portal` registrieren. Aus der heruntergeladenen `google-services.json` werden nur folgende Clientwerte als geschützte Build-Variablen übernommen:

```properties
ML_FIREBASE_APPLICATION_ID=mobilesdk_app_id
ML_FIREBASE_API_KEY=current_key
ML_FIREBASE_PROJECT_ID=project_id
ML_FIREBASE_SENDER_ID=project_number
```

Die App initialisiert Firebase bewusst selbst. `google-services.json` wird nicht benötigt und ist per `.gitignore` ausgeschlossen. Fehlt ein Wert, bleibt FCM aus.

Diese vier Werte identifizieren die Firebase-App und sind laut Firebase keine Server-Zugangsdaten. Trotzdem gehören sie in die geschützte CI-Konfiguration und nicht in den öffentlichen Standard-Build.

## 2. Serverzugang getrennt halten

Für den Versand verwendet ausschließlich der Device Service ein Dienstkonto mit möglichst schmaler FCM-Berechtigung. Die JSON-Datei beziehungsweise der private Schlüssel liegt in einem Secret Store des Servers und niemals:

- im Git-Repository,
- in `gradle.properties`,
- in der Android-App oder APK,
- auf dem Mitarbeitergerät.

Der Server authentifiziert sich für die [FCM HTTP-v1-API](https://firebase.google.com/docs/cloud-messaging/send/v1-api) per OAuth-2.0-Zugriffstoken.

## 3. Nur Data Messages senden

Der Device Service sendet ausschließlich typisierte Werte im `data`-Block. Ein `notification`-Block ist verboten, weil Android ihn im Hintergrund direkt mit servergeliefertem Text anzeigen könnte.

Beispiel:

```json
{
  "message": {
    "token": "<firebase-installation-id>",
    "data": {
      "action": "fetch_notification",
      "event_id": "<opaque-id>",
      "event_type": "open_mail",
      "revision": "1"
    },
    "android": {
      "priority": "HIGH",
      "ttl": "300s"
    }
  }
}
```

FCM erhält keinen Absender, Betreff, Termin, Raum oder Vorschautext. Die App akzeptiert für Detailmeldungen nur `fetch_notification` sowie die Ereignistypen `open_mail`, `open_calendar` und `open_talk`. Benutzer, Gerät und Berechtigung werden unmittelbar vor jedem Versand erneut geprüft. Anschließend lädt nur das freigegebene Gerät die Details über eine signierte HTTPS-Anfrage aus der Bridge.

## 4. Funktionstest

1. signierte App mit den vier Clientwerten bauen und installieren,
2. Gerät mit einem Authentik-Enrollment-Token registrieren und der passenden Authentik Device Access Group zuordnen,
3. Benutzer anmelden und Android-Benachrichtigungen erlauben,
4. am Device Service die gespeicherte Installations-ID und Gerätebindung prüfen,
5. Mail-, Termin- und Talk-Ereignis über den signierten Bridge-Endpunkt einspeisen,
6. prüfen, dass FCM ausschließlich ID, Typ und Revision enthält,
7. verifizieren, dass der Sperrbildschirm keine Fachdaten zeigt und nach dem Entsperren die gewählte Datenschutzstufe greift,
8. antippen und prüfen, dass nur die passende freigegebene Authentik-App geöffnet wird,
9. abmelden und prüfen, dass `DELETE /v1/push/registrations/{device_id}` die Zuordnung entfernt.

Offizielle Grundlagen: [Firebase in Android einrichten](https://firebase.google.com/docs/android/setup), [FCM für Android](https://firebase.google.com/docs/cloud-messaging/android/get-started), [vertrauenswürdige Serverumgebung](https://firebase.google.com/docs/cloud-messaging/server-environment).
