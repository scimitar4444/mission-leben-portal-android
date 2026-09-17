package de.missionleben.portal.push

import android.content.Context

class PushRegistrationStore(context: Context) {
    private val preferences = context.getSharedPreferences("mission_leben_push", Context.MODE_PRIVATE)

    var installationId: String?
        get() = preferences.getString(KEY_INSTALLATION_ID, null)
        set(value) {
            preferences.edit().apply {
                if (value.isNullOrBlank()) remove(KEY_INSTALLATION_ID) else putString(KEY_INSTALLATION_ID, value)
            }.apply()
        }

    var permissionWasRequested: Boolean
        get() = preferences.getBoolean(KEY_PERMISSION_REQUESTED, false)
        set(value) = preferences.edit().putBoolean(KEY_PERMISSION_REQUESTED, value).apply()

    private companion object {
        const val KEY_INSTALLATION_ID = "installation_id"
        const val KEY_PERMISSION_REQUESTED = "permission_requested"
    }
}
