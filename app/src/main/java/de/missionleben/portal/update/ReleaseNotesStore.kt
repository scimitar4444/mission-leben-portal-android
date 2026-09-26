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
        76 to listOf(R.string.release_note_push_check, R.string.release_note_update_summary),
        77 to listOf(R.string.release_note_compact_settings),
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
