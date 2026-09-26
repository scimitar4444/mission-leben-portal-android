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

class ReleaseNotesStore(context: Context) {
    private val preferences = context.getSharedPreferences("mission_leben_release_notes", Context.MODE_PRIVATE)
    private val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)

    fun pendingNotes(): List<Int> {
        val lastSeen = preferences.getInt("last_seen_version_code", 0)
        val notes = when (BuildConfig.VERSION_CODE) {
            76 -> listOf(R.string.release_note_push_check, R.string.release_note_update_summary)
            77 -> if (lastSeen >= 76) {
                listOf(R.string.release_note_compact_settings)
            } else {
                listOf(
                    R.string.release_note_push_check,
                    R.string.release_note_update_summary,
                    R.string.release_note_compact_settings,
                )
            }
            else -> emptyList()
        }
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

    fun markSeen() {
        preferences.edit().putInt("last_seen_version_code", BuildConfig.VERSION_CODE).apply()
    }
}
