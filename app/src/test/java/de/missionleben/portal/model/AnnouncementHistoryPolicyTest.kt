package de.missionleben.portal.model

import org.junit.Assert.*
import org.junit.Test

class AnnouncementHistoryPolicyTest {
    private val now = 2_000_000_000L
    private fun item(id: Long = 1, published: Long = now - 60, expiry: Long = 0, archived: Boolean = false) =
        AnnouncementItem(id, "Synthetic notice", "Readable text", "IT", published, expiry, archived)

    @Test fun sourceDeletedNoticeRemainsReadableButNeverReappearsOnHome() {
        val deleted = item(archived = true)
        assertEquals(listOf(deleted), AnnouncementHistoryPolicy.visible(listOf(deleted), now))
        assertFalse(AnnouncementHistoryPolicy.isActive(deleted, now))
    }
    @Test fun expiredNoticeIsArchivedEvenWithoutServerFlag() {
        val expired = item(expiry = now)
        assertTrue(AnnouncementHistoryPolicy.isArchived(expired, now))
        assertEquals(listOf(expired), AnnouncementHistoryPolicy.visible(listOf(expired), now))
    }
    @Test fun archiveEndsExactlyFourteenDaysAfterPublicationAndInvalidTimesAreRejected() {
        val cutoff = now - AnnouncementHistoryPolicy.RETENTION_SECONDS
        val valid = item(published = cutoff + 1, archived = true)
        assertEquals(listOf(valid), AnnouncementHistoryPolicy.visible(listOf(valid,
            item(2, cutoff, archived = true), item(3, now + 1), item(4, 0), item(0)), now))
    }
    @Test fun activeOldNoticeIsStillLiveButDoesNotGainIndefiniteArchive() {
        val active = item(published = now - 30 * 86400)
        assertEquals(listOf(active), AnnouncementHistoryPolicy.visible(listOf(active), now))
        assertTrue(AnnouncementHistoryPolicy.visible(listOf(active.copy(archived = true)), now).isEmpty())
    }
    @Test fun alreadyReadIsNotADestructiveHistoryFilter() {
        val notice = item()
        val readIds = setOf(notice.id)
        assertTrue(listOf(notice).filter { it.id !in readIds }.isEmpty())
        assertEquals(listOf(notice), AnnouncementHistoryPolicy.visible(listOf(notice), now))
    }
}
