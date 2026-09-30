package de.missionleben.portal.push

import de.missionleben.portal.model.PortalApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NotificationBadgeTargetTest {
    @Test
    fun communicationActionsMapToTheirVisibleApplication() {
        assertEquals(NotificationBadgeTarget.ZIMBRA, NotificationBadgeTarget.fromAction(PushAction.OPEN_MAIL))
        assertEquals(NotificationBadgeTarget.ZIMBRA, NotificationBadgeTarget.fromAction(PushAction.OPEN_CALENDAR))
        assertEquals(NotificationBadgeTarget.TALK, NotificationBadgeTarget.fromAction(PushAction.OPEN_TALK))
        assertEquals(NotificationBadgeTarget.DOCUMENTS, NotificationBadgeTarget.fromAction(PushAction.OPEN_DOCUMENTS))
        assertNull(NotificationBadgeTarget.fromAction(PushAction.REFRESH_SECURITY_STATE))
    }

    @Test
    fun applicationsAreClassifiedWithoutDependingOnDisplayLanguage() {
        val zimbra = PortalApplication("E-Mail", "zimbra-mail", "https://mail.example.invalid/")
        val talk = PortalApplication("Chat", "nextcloud-talk", "https://nextcloud.mission-leben.de/apps/spreed/")
        val other = PortalApplication("Warden", "vaultwarden", "https://vault.example.invalid/")
        val documents = PortalApplication("ML Dokumente", "projectsend-ml-dokumente-test", "https://dokumente.mission-leben.de/auth/oidc/redirect")

        assertEquals(NotificationBadgeTarget.ZIMBRA, NotificationBadgeTarget.fromApplication(zimbra))
        assertEquals(NotificationBadgeTarget.TALK, NotificationBadgeTarget.fromApplication(talk))
        assertEquals(NotificationBadgeTarget.DOCUMENTS, NotificationBadgeTarget.fromApplication(documents))
        assertNull(NotificationBadgeTarget.fromApplication(other))
    }

    @Test
    fun serializedTargetsAreClassifiedSafely() {
        assertEquals(NotificationBadgeTarget.TALK, NotificationBadgeTarget.fromSerialized("TALK"))
        assertNull(NotificationBadgeTarget.fromSerialized("talk"))
        assertNull(NotificationBadgeTarget.fromSerialized("UNKNOWN"))
    }

    @Test
    fun countsAreAggregatedForMailAndCalendarOnZimbra() {
        val counts = NotificationBadgeCounts(zimbra = 4, talk = 2, documents = 1)
        val zimbra = PortalApplication("Zimbra", "zimbra-mail", "https://mail.example.invalid/")
        val talk = PortalApplication("Talk", "talk", "https://nextcloud.mission-leben.de/apps/spreed/")

        assertEquals(4, counts.countFor(zimbra))
        assertEquals(2, counts.countFor(talk))
        assertEquals(1, counts.countFor(PortalApplication("ML Dokumente", "projectsend-ml-dokumente-test", "https://dokumente.mission-leben.de/")))
    }
}
