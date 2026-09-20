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

    private companion object {
        const val KEY_PERMISSION_REQUESTED = "permission_requested"
        const val KEY_PERSONAL_PRIVACY = "personal_privacy"
    }
}
