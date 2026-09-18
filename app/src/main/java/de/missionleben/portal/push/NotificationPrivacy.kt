package de.missionleben.portal.push

import de.missionleben.portal.model.DeviceMode

enum class NotificationPrivacy(
    val wireName: String,
    val label: String,
    val description: String,
) {
    MINIMAL(
        wireName = "minimal",
        label = "Diskret",
        description = "Nur Mail, Termin oder Talk anzeigen.",
    ),
    STANDARD(
        wireName = "standard",
        label = "Standard",
        description = "Absender und Betreff beziehungsweise Terminzeit und Ort anzeigen.",
    ),
    DETAILED(
        wireName = "detailed",
        label = "Ausführlich",
        description = "Zusätzlich eine kurze Mail- oder Talk-Vorschau anzeigen.",
    );

    companion object {
        fun fromWireName(value: String?): NotificationPrivacy? = entries.firstOrNull { it.wireName == value }

        fun effective(mode: DeviceMode?, personalPrivacy: NotificationPrivacy): NotificationPrivacy =
            if (mode == DeviceMode.PERSONAL) personalPrivacy else MINIMAL
    }
}
