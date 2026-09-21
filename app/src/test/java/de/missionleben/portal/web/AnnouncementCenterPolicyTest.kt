package de.missionleben.portal.web

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnnouncementCenterPolicyTest {
    @Test
    fun recognizesOnlyMissionLebenAnnouncementCenterPages() {
        assertTrue(
            AnnouncementCenterPolicy.isAnnouncementPage(
                "https://nextcloud.mission-leben.de/apps/announcementcenter/",
            ),
        )
        assertTrue(
            AnnouncementCenterPolicy.isAnnouncementPage(
                "https://nextcloud.mission-leben.de/index.php/apps/announcementcenter/",
            ),
        )
        assertFalse(
            AnnouncementCenterPolicy.isAnnouncementPage(
                "https://nextcloud.mission-leben.de/apps/spreed/",
            ),
        )
        assertFalse(
            AnnouncementCenterPolicy.isAnnouncementPage(
                "https://other.example/apps/announcementcenter/",
            ),
        )
    }

    @Test
    fun embeddedScriptHidesOnlyTheNextcloudHeader() {
        assertTrue(AnnouncementCenterPolicy.EMBEDDED_SCRIPT.contains("#header"))
        assertTrue(AnnouncementCenterPolicy.EMBEDDED_SCRIPT.contains("data-ml-announcement-embedded"))
    }
}
