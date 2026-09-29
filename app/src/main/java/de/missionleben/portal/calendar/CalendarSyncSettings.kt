package de.missionleben.portal.calendar

import android.content.Context

class CalendarSyncSettings(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("ml_calendar_sync", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = preferences.getBoolean("enabled", false)
        set(value) { preferences.edit().putBoolean("enabled", value).apply() }

    var days: Int
        get() = CalendarSyncPolicy.validDays(preferences.getInt("days", 14))
        set(value) { preferences.edit().putInt("days", CalendarSyncPolicy.validDays(value)).apply() }

    var lastSuccessMillis: Long
        get() = preferences.getLong("last_success", 0L)
        set(value) { preferences.edit().putLong("last_success", value).apply() }

    var ownerSubject: String
        get() = preferences.getString("owner", "").orEmpty()
        set(value) { preferences.edit().putString("owner", value).apply() }
}
