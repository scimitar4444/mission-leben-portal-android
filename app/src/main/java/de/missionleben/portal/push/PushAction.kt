package de.missionleben.portal.push

import androidx.annotation.StringRes
import de.missionleben.portal.R

enum class PushAction(
    val wireName: String,
    val channelId: String,
    @StringRes val titleRes: Int,
    @StringRes val bodyRes: Int,
    val notificationId: Int,
) {
    OPEN_MAIL(
        wireName = "open_mail",
        channelId = "mail",
        titleRes = R.string.push_mail_title,
        bodyRes = R.string.push_mail_body,
        notificationId = 101,
    ),
    OPEN_CALENDAR(
        wireName = "open_calendar",
        channelId = "calendar",
        titleRes = R.string.push_calendar_title,
        bodyRes = R.string.push_calendar_body,
        notificationId = 102,
    ),
    OPEN_TALK(
        wireName = "open_talk",
        channelId = "talk",
        titleRes = R.string.push_talk_title,
        bodyRes = R.string.push_talk_body,
        notificationId = 103,
    ),
    REFRESH_SECURITY_STATE(
        wireName = "refresh_security_state",
        channelId = "security",
        titleRes = R.string.push_security_title,
        bodyRes = R.string.push_security_body,
        notificationId = 104,
    );

    companion object {
        fun fromWireName(value: String?): PushAction? = entries.firstOrNull { it.wireName == value }
    }
}
