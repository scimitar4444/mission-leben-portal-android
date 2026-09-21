package de.missionleben.portal.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NotificationNavigationTest {
    @Test
    fun `talk notification replaces oidc redirect with target room`() {
        val result = NotificationNavigation.resolve(
            action = PushAction.OPEN_TALK,
            targetId = "room_123",
            applicationLaunchUrl = "https://nextcloud.mission-leben.de/apps/user_oidc/login/4?redirectUrl=https%3A%2F%2Fnextcloud.mission-leben.de%2Fapps%2Fspreed%2F",
            zimbraWebBaseUrl = "https://mail.mission-leben.de",
        )

        assertEquals(
            "https://nextcloud.mission-leben.de/apps/user_oidc/login/4?redirectUrl=https%3A%2F%2Fnextcloud.mission-leben.de%2Fcall%2Froom_123",
            result,
        )
    }

    @Test
    fun `mail notification opens the exact zimbra message`() {
        assertEquals(
            "https://mail.mission-leben.de/modern/email/Inbox/message/42",
            NotificationNavigation.resolve(
                action = PushAction.OPEN_MAIL,
                targetId = "42",
                applicationLaunchUrl = "https://id.mission-leben.de/application/saml/zimbra-mail/init/",
                zimbraWebBaseUrl = "https://mail.mission-leben.de",
            ),
        )
    }

    @Test
    fun `mail notification removes delegated zimbra account qualifier`() {
        assertEquals(
            "https://mail.mission-leben.de/modern/email/Inbox/message/200207",
            NotificationNavigation.resolve(
                action = PushAction.OPEN_MAIL,
                targetId = "c92a7e31-0ac3-41ce-8953-aed18ee2774b:200207",
                applicationLaunchUrl = "https://id.mission-leben.de/application/saml/zimbra-mail/init/",
                zimbraWebBaseUrl = "https://mail.mission-leben.de",
            ),
        )
    }

    @Test
    fun `direct zimbra message gets inbox as browser history parent`() {
        assertEquals(
            "https://mail.mission-leben.de/modern/email/Inbox",
            NotificationNavigation.zimbraMailOverviewUrl(
                "https://mail.mission-leben.de/modern/email/Inbox/message/200207",
                "https://mail.mission-leben.de",
            ),
        )
    }

    @Test
    fun `non zimbra targets never get a synthetic browser history parent`() {
        assertNull(
            NotificationNavigation.zimbraMailOverviewUrl(
                "https://evil.example/modern/email/Inbox/message/200207",
                "https://mail.mission-leben.de",
            ),
        )
        assertNull(
            NotificationNavigation.zimbraMailOverviewUrl(
                "https://mail.mission-leben.de/modern/email/Inbox/conversation/42",
                "https://mail.mission-leben.de",
            ),
        )
    }

    @Test
    fun `invalid target falls back to approved application launch url`() {
        val launch = "https://id.mission-leben.de/application/saml/zimbra-mail/init/"
        assertEquals(
            launch,
            NotificationNavigation.resolve(
                action = PushAction.OPEN_MAIL,
                targetId = "../../etc/passwd",
                applicationLaunchUrl = launch,
                zimbraWebBaseUrl = "https://mail.mission-leben.de",
            ),
        )
    }
}
