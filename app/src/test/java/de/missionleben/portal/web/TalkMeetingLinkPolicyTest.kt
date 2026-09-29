package de.missionleben.portal.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TalkMeetingLinkPolicyTest {
    @Test fun `converts both Nextcloud meeting paths to native Talk link`() {
        assertEquals(
            "nextcloudtalk://nextcloud.mission-leben.de/call/Ab12cd34",
            TalkMeetingLinkPolicy.parse("https://nextcloud.mission-leben.de/index.php/call/Ab12cd34")?.appUrl,
        )
        assertEquals(
            "nextcloudtalk://nextcloud.mission-leben.de/call/Ab12cd34",
            TalkMeetingLinkPolicy.parse("https://nextcloud.mission-leben.de/call/Ab12cd34?foo=bar")?.appUrl,
        )
    }

    @Test fun `does not redirect ordinary chat or untrusted hosts`() {
        listOf(
            "https://nextcloud.mission-leben.de/apps/spreed/",
            "https://nextcloud.mission-leben.de/call/short-token",
            "https://nextcloud.mission-leben.de.attacker.example/call/Ab12cd34",
            "http://nextcloud.mission-leben.de/call/Ab12cd34",
            "https://user@nextcloud.mission-leben.de/call/Ab12cd34",
            "https://nextcloud.mission-leben.de:444/call/Ab12cd34",
        ).forEach { assertNull(TalkMeetingLinkPolicy.parse(it)) }
    }
}
