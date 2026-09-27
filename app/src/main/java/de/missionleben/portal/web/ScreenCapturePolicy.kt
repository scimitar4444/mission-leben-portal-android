package de.missionleben.portal.web

import de.missionleben.portal.model.DeviceMode

/** Authentication, enrollment and shared-device pages must never inherit the personal screenshot opt-in. */
object ScreenCapturePolicy {
    fun protect(
        mode: DeviceMode?,
        personalOptIn: Boolean,
        appContent: Boolean,
        authentikPage: Boolean,
    ): Boolean = mode != DeviceMode.PERSONAL ||
        !personalOptIn ||
        !appContent ||
        authentikPage
}
