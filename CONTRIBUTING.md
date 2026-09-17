# Mitwirken

Danke für dein Interesse.

1. Issue mit Problem, Ziel und Sicherheitsauswirkung anlegen.
2. Branch vom aktuellen `main` erstellen.
3. Keine Secrets, produktiven Tokens, Benutzer- oder Gerätedaten committen.
4. `./gradlew test assembleDebug` ausführen.
5. Pull Request mit Testhinweisen und gegebenenfalls Authentik-Migrationsschritten öffnen.

Änderungen an OIDC, Token-Speicherung, Enrollment oder Geräte-Handoff benötigen mindestens ein Review mit Sicherheitsfokus. Neue Geräteaktionen müssen als enge, typisierte Capability entworfen werden; ein allgemeines „URL/Befehl öffnen“ wird nicht akzeptiert.
