package de.missionleben.portal.push

import org.junit.Assert.assertEquals
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
