package de.missionleben.portal.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseNotesPolicyTest {
    @Test fun appearsOnceAfterUpdateButNotFreshInstall() {
        assertTrue(ReleaseNotesPolicy.shouldShow(0, 76, 1000, 2000, true))
        assertTrue(ReleaseNotesPolicy.shouldShow(75, 76, 1000, 2000, true))
        assertFalse(ReleaseNotesPolicy.shouldShow(0, 76, 1000, 1000, true))
        assertFalse(ReleaseNotesPolicy.shouldShow(76, 76, 1000, 2000, true))
        assertFalse(ReleaseNotesPolicy.shouldShow(75, 76, 1000, 2000, false))
    }
}
