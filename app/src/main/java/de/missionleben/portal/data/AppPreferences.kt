package de.missionleben.portal.data

import android.content.Context
import de.missionleben.portal.model.DeviceMode
import de.missionleben.portal.model.EnrollmentState

class AppPreferences(context: Context) {
    private val preferences = context.getSharedPreferences("mission_leben_settings", Context.MODE_PRIVATE)

    var deviceMode: DeviceMode?
        get() = preferences.getString(KEY_DEVICE_MODE, null)?.let(DeviceMode::valueOf)
        set(value) {
            preferences.edit().apply {
                if (value == null) remove(KEY_DEVICE_MODE) else putString(KEY_DEVICE_MODE, value.name)
            }.apply()
        }

    var deviceId: String?
        get() = preferences.getString(KEY_DEVICE_ID, null)
        set(value) {
            preferences.edit().apply {
                if (value == null) remove(KEY_DEVICE_ID) else putString(KEY_DEVICE_ID, value)
            }.apply()
        }

    var enrollmentState: EnrollmentState
        get() = preferences.getString(KEY_ENROLLMENT_STATE, null)
            ?.let(EnrollmentState::valueOf)
            ?: EnrollmentState.NOT_ENROLLED
        set(value) = preferences.edit().putString(KEY_ENROLLMENT_STATE, value.name).apply()

    fun clearProfile() {
        preferences.edit()
            .remove(KEY_DEVICE_MODE)
            .remove(KEY_DEVICE_ID)
            .remove(KEY_ENROLLMENT_STATE)
            .apply()
    }

    private companion object {
        const val KEY_DEVICE_MODE = "device_mode"
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_ENROLLMENT_STATE = "enrollment_state"
    }
}
