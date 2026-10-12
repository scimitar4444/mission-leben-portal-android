package de.missionleben.portal.model

import de.missionleben.portal.push.InboxNotice
import de.missionleben.portal.push.PushAction
import org.junit.Assert.*
import org.junit.Test

class NoticeInboxPolicyTest {
    private val documents = PortalApplication("ML Dokumente", "projectsend-ml-dokumente-test", "https://dokumente.mission-leben.de/")
    private val helpdesk = PortalApplication("IT Hilfe", "otobo-mitarbeiter", "https://it-service.mission-leben.de:8443/otobo/customer.pl")
    private val notices = listOf(
        InboxNotice("documents-event-012345", PushAction.OPEN_DOCUMENTS, 1),
        InboxNotice("helpdesk-event-0123456", PushAction.OPEN_HELPDESK, 2),
        InboxNotice("mail-event-01234567890", PushAction.OPEN_MAIL, 3),
    )

    @Test fun personalInboxIncludesOnlyCurrentlyApprovedApplications() {
        assertEquals(notices.take(2), NoticeInboxPolicy.visible(notices, listOf(documents, helpdesk), DeviceMode.PERSONAL))
        assertEquals(listOf(notices[1]), NoticeInboxPolicy.visible(notices, listOf(helpdesk), DeviceMode.PERSONAL))
        assertTrue(NoticeInboxPolicy.visible(notices, emptyList(), DeviceMode.PERSONAL).isEmpty())
    }

    @Test fun sharedDevicesDoNotExposePersonalDocumentsAndLookalikeSlugsDoNotGrantAccess() {
        assertEquals(listOf(notices[1]), NoticeInboxPolicy.visible(notices, listOf(documents, helpdesk), DeviceMode.SHARED))
        assertTrue(NoticeInboxPolicy.visible(notices, listOf(documents.copy(slug = "fake"), helpdesk.copy(slug = "fake-it")), DeviceMode.PERSONAL).isEmpty())
    }
}
