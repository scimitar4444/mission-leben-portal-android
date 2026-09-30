package de.missionleben.portal.web

import de.missionleben.portal.model.DeviceMode
import de.missionleben.portal.model.EnrollmentProfile
import de.missionleben.portal.model.EnrollmentState

/** Public exports are a deliberate action by an unlocked personal employee. */
object PersonalDownloadPolicy {
    fun allowsExport(
        browserMode: DeviceMode?,
        registeredMode: DeviceMode?,
        profile: EnrollmentProfile?,
        state: EnrollmentState,
        applicationContent: Boolean,
        reauthenticationRequired: Boolean,
        locked: Boolean,
    ): Boolean = browserMode == DeviceMode.PERSONAL &&
        registeredMode == DeviceMode.PERSONAL &&
        profile == EnrollmentProfile.PERSONAL_EMPLOYEE &&
        state == EnrollmentState.TRUSTED && applicationContent &&
        !reauthenticationRequired && !locked
}
