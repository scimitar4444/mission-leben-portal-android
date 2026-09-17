package de.missionleben.portal.push

enum class PushAction(
    val wireName: String,
    val channelId: String,
    val title: String,
    val body: String,
    val notificationId: Int,
) {
    OPEN_MAIL(
        wireName = "open_mail",
        channelId = "mail",
        title = "Neue Mail",
        body = "Zimbra öffnen",
        notificationId = 101,
    ),
    OPEN_CALENDAR(
        wireName = "open_calendar",
        channelId = "calendar",
        title = "Termin",
        body = "Ein Termin steht bevor.",
        notificationId = 102,
    ),
    OPEN_TALK(
        wireName = "open_talk",
        channelId = "talk",
        title = "Talk",
        body = "Neue Talk-Aktivität.",
        notificationId = 103,
    ),
    REFRESH_SECURITY_STATE(
        wireName = "refresh_security_state",
        channelId = "security",
        title = "Gerätesicherheit",
        body = "Sicherheitsstatus bitte in der App prüfen.",
        notificationId = 104,
    );

    companion object {
        fun fromWireName(value: String?): PushAction? = entries.firstOrNull { it.wireName == value }
    }
}
