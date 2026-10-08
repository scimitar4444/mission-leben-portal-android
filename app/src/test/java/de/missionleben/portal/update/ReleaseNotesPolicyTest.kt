package de.missionleben.portal.update

import de.missionleben.portal.BuildConfig
import de.missionleben.portal.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseNotesPolicyTest {
    @Test fun currentVersionAlwaysHasReopenableNotes() {
        assertTrue(ReleaseNotesCatalog.forVersion(BuildConfig.VERSION_CODE).isNotEmpty())
    }

    @Test fun calendarReleaseHasReopenableCapacityNote() {
        assertEquals(
            listOf(R.string.release_note_calendar_capacity),
            ReleaseNotesCatalog.forVersion(89),
        )
    }

    @Test fun talkStartReleaseHasOnlyTheRequestedPublicNote() {
        assertEquals(
            listOf(R.string.release_note_talk_start_setting),
            ReleaseNotesCatalog.forVersion(88),
        )
    }

    @Test fun upgradeNotesIncludeOnlyUnseenVersions() {
        assertEquals(
            listOf(R.string.release_note_compact_settings),
            ReleaseNotesCatalog.since(76, 77),
        )
        assertEquals(
            ReleaseNotesCatalog.forVersion(76) + ReleaseNotesCatalog.forVersion(77),
            ReleaseNotesCatalog.since(75, 77),
        )
        assertEquals(
            listOf(R.string.release_note_personal_lock),
            ReleaseNotesCatalog.since(77, 78),
        )
        assertEquals(
            listOf(R.string.release_note_screenshots_disabled),
            ReleaseNotesCatalog.since(81, 82),
        )
    }

    @Test fun appearsOnceAfterUpdateButNotFreshInstall() {
        assertTrue(ReleaseNotesPolicy.shouldShow(0, 76, 1000, 2000, true))
        assertTrue(ReleaseNotesPolicy.shouldShow(75, 76, 1000, 2000, true))
        assertFalse(ReleaseNotesPolicy.shouldShow(0, 76, 1000, 1000, true))
        assertFalse(ReleaseNotesPolicy.shouldShow(76, 76, 1000, 2000, true))
        assertFalse(ReleaseNotesPolicy.shouldShow(75, 76, 1000, 2000, false))
    }
}
