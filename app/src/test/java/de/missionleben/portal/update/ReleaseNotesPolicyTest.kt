package de.missionleben.portal.update

import de.missionleben.portal.BuildConfig
import de.missionleben.portal.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseNotesPolicyTest {
    @Test fun webSessionBugfixExplainsStorageAndLoginHintsWithoutRepeatingOldNotes() {
        val current = listOf(R.string.release_note_web_cookie_storage, R.string.release_note_clear_login_errors)
        assertEquals(current, ReleaseNotesCatalog.forVersion(95))
        assertEquals(current, ReleaseNotesCatalog.since(94, 95))
    }

    @Test fun photoHelpReleaseHasNotesForStableAndPilotUpgrades() {
        val current = listOf(
            R.string.release_note_ticket_photo,
            R.string.release_note_app_help,
            R.string.release_note_phone_settings,
        )
        assertEquals(current, ReleaseNotesCatalog.forVersion(94))
        assertEquals(current, ReleaseNotesCatalog.since(93, 94))
        assertEquals(
            listOf(R.string.release_note_helpdesk_push, R.string.release_note_shortcuts) + current,
            ReleaseNotesCatalog.since(91, 94),
        )
    }

    @Test fun shortcutsReleaseExplainsFavoritesInboxAndProblemReport() {
        assertEquals(listOf(R.string.release_note_shortcuts), ReleaseNotesCatalog.forVersion(93))
    }
    @Test fun employeeOverviewReleaseIncludesAllPublicChanges() {
        val expected = listOf(
            R.string.release_note_today,
            R.string.release_note_contact_favorites,
            R.string.release_note_compact_documents,
            R.string.release_note_mobile_helpdesk,
            R.string.release_note_talk_setup_choice,
        )
        assertEquals(expected, ReleaseNotesCatalog.forVersion(91))
        assertEquals(expected, ReleaseNotesCatalog.since(90, 91))
    }

    @Test fun currentVersionAlwaysHasReopenableNotes() {
        assertTrue(ReleaseNotesCatalog.forVersion(BuildConfig.VERSION_CODE).isNotEmpty())
    }

    @Test fun sessionFixReleaseIncludesPasswordEyeAndTwoPersonalPhones() {
        assertEquals(
            listOf(
                R.string.release_note_web_session_fix,
                R.string.release_note_password_eye,
                R.string.release_note_two_personal_handsets,
            ),
            ReleaseNotesCatalog.forVersion(90),
        )
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
