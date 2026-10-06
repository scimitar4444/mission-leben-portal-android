package de.missionleben.portal.data

import android.content.Context
import de.missionleben.portal.model.DeviceMode
import de.missionleben.portal.model.EnrollmentState
import de.missionleben.portal.model.EnrollmentProfile
import java.security.MessageDigest

class AppPreferences(context: Context) {
    private val preferences = context.getSharedPreferences("mission_leben_settings", Context.MODE_PRIVATE)

    init {
        // A previous pilot allowed screenshots of personal WebViews. Revoke that
        // opt-in on upgrade so no stale preference can re-enable capture.
        if (preferences.contains(KEY_ALLOW_PERSONAL_SCREENSHOTS)) {
            preferences.edit().remove(KEY_ALLOW_PERSONAL_SCREENSHOTS).apply()
        }
    }

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

    var enrollmentProfile: EnrollmentProfile?
        get() = preferences.getString(KEY_ENROLLMENT_PROFILE, null)
            ?.let { runCatching { EnrollmentProfile.valueOf(it) }.getOrNull() }
        set(value) {
            preferences.edit().apply {
                if (value == null) remove(KEY_ENROLLMENT_PROFILE)
                else putString(KEY_ENROLLMENT_PROFILE, value.name)
            }.apply()
        }

    var enrollmentState: EnrollmentState
        get() = preferences.getString(KEY_ENROLLMENT_STATE, null)
            ?.let(EnrollmentState::valueOf)
            ?: EnrollmentState.NOT_ENROLLED
        set(value) = preferences.edit().putString(KEY_ENROLLMENT_STATE, value.name).apply()

    var downloadsAutoOpenEnabled: Boolean
        get() = preferences.getBoolean(KEY_DOWNLOADS_AUTO_OPEN, true)
        set(value) = preferences.edit().putBoolean(KEY_DOWNLOADS_AUTO_OPEN, value).apply()

    var talkStartAtLastChat: Boolean
        get() = preferences.getBoolean(KEY_TALK_START_AT_LAST_CHAT, false)
        set(value) = preferences.edit().putBoolean(KEY_TALK_START_AT_LAST_CHAT, value).apply()

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

    var absoluteDeadlineReauthenticationRequired: Boolean
        get() = hasCurrentPersonalBinding() && preferences.getBoolean(KEY_REAUTHENTICATION_ABSOLUTE, false)
        set(value) = preferences.edit().putBoolean(KEY_REAUTHENTICATION_ABSOLUTE, value).apply()

    val boundPersonalSubject: String?
        get() = if (hasCurrentPersonalBinding()) preferences.getString(KEY_BOUND_SUBJECT, null) else null

    val boundPersonalLoginHint: String?
        get() = if (hasCurrentPersonalBinding()) reauthenticationHint?.trim()?.takeIf(String::isNotBlank) else null

    val boundPersonalAuthenticatedAtEpochSeconds: Long
        get() = if (hasCurrentPersonalBinding()) preferences.getLong(KEY_BOUND_AUTHENTICATED_AT, 0L) else 0L

    private fun hasCurrentPersonalBinding(): Boolean =
        deviceMode == DeviceMode.PERSONAL && enrollmentState == EnrollmentState.TRUSTED &&
            !deviceId.isNullOrBlank() && deviceId == preferences.getString(KEY_LOGIN_HINT_DEVICE, null) &&
            !preferences.getString(KEY_BOUND_SUBJECT, null).isNullOrBlank()

    fun rememberPersonalIdentity(subject: String, loginHint: String, authenticatedAtEpochSeconds: Long = 0L) {
        if (deviceMode != DeviceMode.PERSONAL || enrollmentState != EnrollmentState.TRUSTED ||
            deviceId.isNullOrBlank() || subject.isBlank()) return
        preferences.edit().apply {
            if (loginHint.isBlank()) remove(KEY_REAUTHENTICATION_HINT)
            else putString(KEY_REAUTHENTICATION_HINT, loginHint.trim())
        }
            .putString(KEY_LOGIN_HINT_DEVICE, deviceId)
            .putString(KEY_BOUND_SUBJECT, subject)
            .putLong(KEY_BOUND_AUTHENTICATED_AT, authenticatedAtEpochSeconds.coerceAtLeast(0L))
            .putBoolean(KEY_REAUTHENTICATION_REQUIRED, false)
            .putBoolean(KEY_REAUTHENTICATION_ABSOLUTE, false)
            .apply()
    }

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

    fun clearReauthentication(keepBoundPersonalIdentity: Boolean = false) {
        if (keepBoundPersonalIdentity && hasCurrentPersonalBinding()) {
            reauthenticationRequired = false
            absoluteDeadlineReauthenticationRequired = false
            return
        }
        preferences.edit()
            .remove(KEY_REAUTHENTICATION_HINT)
            .remove(KEY_REAUTHENTICATION_REQUIRED)
            .remove(KEY_REAUTHENTICATION_ABSOLUTE)
            .remove(KEY_LOGIN_HINT_DEVICE)
            .remove(KEY_BOUND_SUBJECT)
            .remove(KEY_BOUND_AUTHENTICATED_AT)
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
        clearReauthentication()
        preferences.edit()
            .remove(KEY_DEVICE_MODE)
            .remove(KEY_DEVICE_ID)
            .remove(KEY_ENROLLMENT_PROFILE)
            .remove(KEY_ENROLLMENT_STATE)
            .remove(KEY_REAUTHENTICATION_HINT)
            .remove(KEY_REAUTHENTICATION_REQUIRED)
            .remove(KEY_SHARED_SESSION_SCREEN_TURNED_OFF)
            .remove(KEY_ALLOW_PERSONAL_SCREENSHOTS)
            .remove(KEY_DOWNLOADS_AUTO_OPEN)
            .remove(KEY_TALK_START_AT_LAST_CHAT)
            .apply()
        clearAnnouncementReadState()
    }

    private fun announcementReadKey(subject: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(subject.toByteArray(Charsets.UTF_8))
        val suffix = digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return KEY_ANNOUNCEMENTS_READ_PREFIX + suffix
    }

    private companion object {
        const val KEY_LOGIN_HINT_DEVICE = "login_hint_device"
        const val KEY_BOUND_SUBJECT = "bound_login_subject"
        const val KEY_BOUND_AUTHENTICATED_AT = "bound_login_authenticated_at"
        const val KEY_DEVICE_MODE = "device_mode"
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_ENROLLMENT_PROFILE = "enrollment_profile"
        const val KEY_ENROLLMENT_STATE = "enrollment_state"
        const val KEY_DOWNLOADS_AUTO_OPEN = "downloads_auto_open"
        const val KEY_TALK_START_AT_LAST_CHAT = "talk_start_at_last_chat"
        const val KEY_REAUTHENTICATION_HINT = "reauthentication_hint"
        const val KEY_REAUTHENTICATION_REQUIRED = "reauthentication_required"
        const val KEY_REAUTHENTICATION_ABSOLUTE = "reauthentication_absolute_deadline"
        const val KEY_SHARED_SESSION_SCREEN_TURNED_OFF = "shared_session_screen_turned_off"
        const val KEY_ALLOW_PERSONAL_SCREENSHOTS = "allow_personal_screenshots"
        const val KEY_ANNOUNCEMENTS_READ_PREFIX = "announcements_read_"
        const val MAX_READ_ANNOUNCEMENTS = 100
    }
}
