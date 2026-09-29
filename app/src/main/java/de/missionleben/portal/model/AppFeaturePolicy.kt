package de.missionleben.portal.model

import de.missionleben.portal.calendar.CalendarSyncPolicy
import de.missionleben.portal.push.NotificationBadgeTarget

/** Only effectively visible, supported applications may expose their optional controls. */
data class AppFeatures(val zimbra: Boolean, val talk: Boolean) {
    val communication: Boolean get() = zimbra || talk

    fun canHandoffTalk(capabilities: Set<PortalCapability>): Boolean =
        talk && PortalCapability.OPEN_TALK in capabilities
}

object AppFeaturePolicy {
    fun from(applications: List<PortalApplication>): AppFeatures = AppFeatures(
        zimbra = CalendarSyncPolicy.zimbraVisible(applications.map(PortalApplication::slug)),
        talk = applications.any { NotificationBadgeTarget.fromApplication(it) == NotificationBadgeTarget.TALK },
    )
}
