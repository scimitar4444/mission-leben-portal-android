package de.missionleben.portal.update

import android.content.Context
import de.missionleben.portal.BuildConfig
import de.missionleben.portal.R

object ReleaseNotesPolicy {
    fun shouldShow(
        lastSeenVersion: Int,
        currentVersion: Int,
        firstInstallTime: Long,
        lastUpdateTime: Long,
        hasNotes: Boolean,
    ): Boolean = hasNotes && lastSeenVersion < currentVersion &&
        (lastSeenVersion > 0 || (firstInstallTime > 0 && lastUpdateTime > firstInstallTime))
}

object ReleaseNotesCatalog {
    private val notesByVersion = sortedMapOf(
        100 to listOf(R.string.release_note_talk_voice, R.string.release_note_talk_voice_setup),
        98 to listOf(R.string.release_note_announcement_history),
        76 to listOf(R.string.release_note_push_check, R.string.release_note_update_summary),
        77 to listOf(R.string.release_note_compact_settings),
        78 to listOf(R.string.release_note_personal_lock),
        79 to listOf(R.string.release_note_calendar_pilot),
        80 to listOf(R.string.release_note_talk_calendar),
        81 to listOf(R.string.release_note_setup_assistant),
        82 to listOf(R.string.release_note_screenshots_disabled),
        83 to listOf(R.string.release_note_documents),
        84 to listOf(R.string.release_note_documents_channel_fix),
        85 to listOf(R.string.release_note_personal_downloads),
        86 to listOf(R.string.release_note_existing_device_download_fix, R.string.release_note_download_auto_open_setting),
        87 to listOf(R.string.release_note_secure_unlock, R.string.release_note_reauthentication, R.string.release_note_app_symbols),
        88 to listOf(R.string.release_note_talk_start_setting),
        89 to listOf(R.string.release_note_calendar_capacity),
        90 to listOf(
            R.string.release_note_web_session_fix,
            R.string.release_note_password_eye,
            R.string.release_note_two_personal_handsets,
        ),
        91 to listOf(
            R.string.release_note_today,
            R.string.release_note_contact_favorites,
            R.string.release_note_compact_documents,
            R.string.release_note_mobile_helpdesk,
            R.string.release_note_talk_setup_choice,
        ),
        92 to listOf(R.string.release_note_helpdesk_push),
        93 to listOf(R.string.release_note_shortcuts),
        94 to listOf(
            R.string.release_note_ticket_photo,
            R.string.release_note_app_help,
            R.string.release_note_phone_settings,
        ),
        96 to listOf(R.string.release_note_announcement_push, R.string.release_note_home_notice_read),
        95 to listOf(
            R.string.release_note_web_cookie_storage,
            R.string.release_note_clear_login_errors,
        ),
    )

    fun forVersion(versionCode: Int): List<Int> = notesByVersion[versionCode].orEmpty()

    fun since(lastSeenVersion: Int, currentVersion: Int): List<Int> = notesByVersion
        .filterKeys { it > lastSeenVersion && it <= currentVersion }
        .values
        .flatten()
}

class ReleaseNotesStore(context: Context) {
    private val preferences = context.getSharedPreferences("mission_leben_release_notes", Context.MODE_PRIVATE)
    private val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)

    fun pendingNotes(): List<Int> {
        val lastSeen = preferences.getInt("last_seen_version_code", 0)
        val notes = ReleaseNotesCatalog.since(lastSeen, BuildConfig.VERSION_CODE)
        if (!ReleaseNotesPolicy.shouldShow(
                lastSeen,
                BuildConfig.VERSION_CODE,
                packageInfo.firstInstallTime,
                packageInfo.lastUpdateTime,
                notes.isNotEmpty(),
            )
        ) {
            if (lastSeen < BuildConfig.VERSION_CODE) markSeen()
            return emptyList()
        }
        return notes
    }

    fun currentVersionNotes(): List<Int> = ReleaseNotesCatalog.forVersion(BuildConfig.VERSION_CODE)

    fun markSeen() {
        preferences.edit().putInt("last_seen_version_code", BuildConfig.VERSION_CODE).apply()
    }
}
