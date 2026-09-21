package de.missionleben.portal.security

import de.missionleben.portal.model.DeviceMode

object SharedSessionLifecyclePolicy {
    fun shouldTrack(mode: DeviceMode?): Boolean = mode == DeviceMode.SHARED

    fun shouldInvalidate(
        mode: DeviceMode?,
        screenTurnedOff: Boolean,
    ): Boolean = shouldTrack(mode) && screenTurnedOff
}
