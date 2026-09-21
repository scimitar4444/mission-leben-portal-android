package de.missionleben.portal.push

import android.content.Context
import java.time.ZonedDateTime

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

    var communicationNotificationsEnabled: Boolean
        get() = preferences.getBoolean(KEY_COMMUNICATION_ENABLED, true)
        set(value) = preferences.edit().putBoolean(KEY_COMMUNICATION_ENABLED, value).apply()

    var quietHoursEnabled: Boolean
        get() = preferences.getBoolean(KEY_QUIET_HOURS_ENABLED, false)
        set(value) = preferences.edit().putBoolean(KEY_QUIET_HOURS_ENABLED, value).apply()

    var quietStartMinutes: Int
        get() = preferences.getInt(KEY_QUIET_START_MINUTES, DEFAULT_QUIET_START_MINUTES)
            .takeIf(NotificationDeliveryPolicy::validMinuteOfDay)
            ?: DEFAULT_QUIET_START_MINUTES
        set(value) {
            require(NotificationDeliveryPolicy.validMinuteOfDay(value))
            preferences.edit().putInt(KEY_QUIET_START_MINUTES, value).apply()
        }

    var quietEndMinutes: Int
        get() = preferences.getInt(KEY_QUIET_END_MINUTES, DEFAULT_QUIET_END_MINUTES)
            .takeIf(NotificationDeliveryPolicy::validMinuteOfDay)
            ?: DEFAULT_QUIET_END_MINUTES
        set(value) {
            require(NotificationDeliveryPolicy.validMinuteOfDay(value))
            preferences.edit().putInt(KEY_QUIET_END_MINUTES, value).apply()
        }

    fun communicationAllowed(now: ZonedDateTime = ZonedDateTime.now()): Boolean =
        NotificationDeliveryPolicy.shouldDeliver(
            communicationEnabled = communicationNotificationsEnabled,
            quietHoursEnabled = quietHoursEnabled,
            quietStartMinutes = quietStartMinutes,
            quietEndMinutes = quietEndMinutes,
            currentMinutes = now.hour * 60 + now.minute,
        )

    companion object {
        val SUPPORTED_CALENDAR_REMINDER_MINUTES = listOf(5, 10, 15, 30)
        const val DEFAULT_CALENDAR_REMINDER_MINUTES = 15
        const val DEFAULT_QUIET_START_MINUTES = 22 * 60
        const val DEFAULT_QUIET_END_MINUTES = 6 * 60

        const val KEY_PERMISSION_REQUESTED = "permission_requested"
        const val KEY_PERSONAL_PRIVACY = "personal_privacy"
        const val KEY_CALENDAR_REMINDER_MINUTES = "calendar_reminder_minutes"
        const val KEY_COMMUNICATION_ENABLED = "communication_notifications_enabled"
        const val KEY_QUIET_HOURS_ENABLED = "quiet_hours_enabled"
        const val KEY_QUIET_START_MINUTES = "quiet_start_minutes"
        const val KEY_QUIET_END_MINUTES = "quiet_end_minutes"
    }
}
