package de.missionleben.portal.model

import de.missionleben.portal.push.InboxNotice
import de.missionleben.portal.push.NotificationNavigation
import de.missionleben.portal.push.PushAction

object NoticeInboxPolicy {
    fun visible(notices: List<InboxNotice>, applications: List<PortalApplication>, mode: DeviceMode?): List<InboxNotice> =
        notices.filter { when (it.action) {
            PushAction.OPEN_DOCUMENTS -> mode == DeviceMode.PERSONAL && NotificationNavigation.approvedDocumentsApplication(applications) != null
            PushAction.OPEN_HELPDESK -> NotificationNavigation.approvedHelpdeskApplication(applications) != null
            else -> false
        } }
}
