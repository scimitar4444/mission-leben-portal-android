package de.missionleben.portal.push

import android.content.Context

class PushRegistrationStore(context: Context) {
    private val preferences = context.getSharedPreferences("mission_leben_push", Context.MODE_PRIVATE)

    var permissionWasRequested: Boolean
        get() = preferences.getBoolean(KEY_PERMISSION_REQUESTED, false)
        set(value) = preferences.edit().putBoolean(KEY_PERMISSION_REQUESTED, value).apply()

    var personalPrivacy: NotificationPrivacy
        get() = NotificationPrivacy.fromWireName(preferences.getString(KEY_PERSONAL_PRIVACY, null))
            ?: NotificationPrivacy.STANDARD
        set(value) = preferences.edit().putString(KEY_PERSONAL_PRIVACY, value.wireName).apply()

    var calendarReminderMinutes: Int
        get() = preferences.getInt(KEY_CALENDAR_REMINDER_MINUTES, DEFAULT_CALENDAR_REMINDER_MINUTES)
            .takeIf(SUPPORTED_CALENDAR_REMINDER_MINUTES::contains)
            ?: DEFAULT_CALENDAR_REMINDER_MINUTES
        set(value) {
            require(value in SUPPORTED_CALENDAR_REMINDER_MINUTES)
            preferences.edit().putInt(KEY_CALENDAR_REMINDER_MINUTES, value).apply()
        }

    companion object {
        val SUPPORTED_CALENDAR_REMINDER_MINUTES = listOf(5, 10, 15, 30)
        const val DEFAULT_CALENDAR_REMINDER_MINUTES = 15

        const val KEY_PERMISSION_REQUESTED = "permission_requested"
        const val KEY_PERSONAL_PRIVACY = "personal_privacy"
        const val KEY_CALENDAR_REMINDER_MINUTES = "calendar_reminder_minutes"
    }
}
