package de.missionleben.portal.data

import android.content.Context
import de.missionleben.portal.model.DeviceMode
import de.missionleben.portal.model.EnrollmentState
import java.security.MessageDigest

class AppPreferences(context: Context) {
    private val preferences = context.getSharedPreferences("mission_leben_settings", Context.MODE_PRIVATE)

    var deviceMode: DeviceMode?
        get() = preferences.getString(KEY_DEVICE_MODE, null)?.let(DeviceMode::valueOf)
        set(value) {
            preferences.edit().apply {
                if (value == null) remove(KEY_DEVICE_MODE) else putString(KEY_DEVICE_MODE, value.name)
                if (value != DeviceMode.SHARED) {
                    remove(KEY_SHARED_SESSION_SCREEN_TURNED_OFF)
                }
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

    var reauthenticationHint: String?
        get() = preferences.getString(KEY_REAUTHENTICATION_HINT, null)
        set(value) {
            preferences.edit().apply {
                if (value.isNullOrBlank()) remove(KEY_REAUTHENTICATION_HINT)
                else putString(KEY_REAUTHENTICATION_HINT, value)
            }.apply()
        }

    var reauthenticationRequired: Boolean
        get() = preferences.getBoolean(KEY_REAUTHENTICATION_REQUIRED, false)
        set(value) = preferences.edit().putBoolean(KEY_REAUTHENTICATION_REQUIRED, value).apply()

    fun markSharedSessionScreenTurnedOff() {
        preferences.edit().putBoolean(KEY_SHARED_SESSION_SCREEN_TURNED_OFF, true).apply()
    }

    fun consumeSharedSessionScreenTurnedOff(): Boolean {
        val screenTurnedOff = preferences.getBoolean(KEY_SHARED_SESSION_SCREEN_TURNED_OFF, false)
        preferences.edit()
            .remove(KEY_SHARED_SESSION_SCREEN_TURNED_OFF)
            .apply()
        return screenTurnedOff
    }

    fun clearReauthentication() {
        preferences.edit()
            .remove(KEY_REAUTHENTICATION_HINT)
            .remove(KEY_REAUTHENTICATION_REQUIRED)
            .apply()
    }

    fun readAnnouncementIds(subject: String): Set<Long> {
        if (subject.isBlank()) return emptySet()
        return preferences.getStringSet(announcementReadKey(subject), emptySet())
            .orEmpty()
            .mapNotNull(String::toLongOrNull)
            .toSet()
    }

    fun markAnnouncementRead(subject: String, announcementId: Long) {
        if (subject.isBlank() || announcementId <= 0L) return
        val ids = (readAnnouncementIds(subject) + announcementId)
            .sortedDescending()
            .take(MAX_READ_ANNOUNCEMENTS)
            .map(Long::toString)
            .toSet()
        preferences.edit().putStringSet(announcementReadKey(subject), ids).apply()
    }

    fun clearAnnouncementReadState() {
        val editor = preferences.edit()
        preferences.all.keys
            .filter { it.startsWith(KEY_ANNOUNCEMENTS_READ_PREFIX) }
            .forEach(editor::remove)
        editor.apply()
    }

    fun clearProfile() {
        preferences.edit()
            .remove(KEY_DEVICE_MODE)
            .remove(KEY_DEVICE_ID)
            .remove(KEY_ENROLLMENT_STATE)
            .remove(KEY_REAUTHENTICATION_HINT)
            .remove(KEY_REAUTHENTICATION_REQUIRED)
            .remove(KEY_SHARED_SESSION_SCREEN_TURNED_OFF)
            .apply()
        clearAnnouncementReadState()
    }

    private fun announcementReadKey(subject: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(subject.toByteArray(Charsets.UTF_8))
        val suffix = digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return KEY_ANNOUNCEMENTS_READ_PREFIX + suffix
    }

    private companion object {
        const val KEY_DEVICE_MODE = "device_mode"
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_ENROLLMENT_STATE = "enrollment_state"
        const val KEY_REAUTHENTICATION_HINT = "reauthentication_hint"
        const val KEY_REAUTHENTICATION_REQUIRED = "reauthentication_required"
        const val KEY_SHARED_SESSION_SCREEN_TURNED_OFF = "shared_session_screen_turned_off"
        const val KEY_ANNOUNCEMENTS_READ_PREFIX = "announcements_read_"
        const val MAX_READ_ANNOUNCEMENTS = 100
    }
}
