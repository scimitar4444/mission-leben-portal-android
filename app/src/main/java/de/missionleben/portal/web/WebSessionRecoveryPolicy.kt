package de.missionleben.portal.web

import de.missionleben.portal.auth.ReauthenticationPolicy
import de.missionleben.portal.model.DeviceMode
import de.missionleben.portal.model.EnrollmentState

/** A browser login redirect is not evidence that a native OIDC session expired. */
object WebSessionRecoveryPolicy {
    fun canPreservePersonalSession(
        mode: DeviceMode?,
        enrollmentState: EnrollmentState,
        signedIn: Boolean,
        authenticatedAtEpochSeconds: Long,
        nativeSessionVerified: Boolean,
        nowEpochSeconds: Long = System.currentTimeMillis() / 1_000L,
    ): Boolean = mode == DeviceMode.PERSONAL &&
        enrollmentState == EnrollmentState.TRUSTED && signedIn && nativeSessionVerified &&
        authenticatedAtEpochSeconds in 1L..nowEpochSeconds &&
        !ReauthenticationPolicy.hasReachedAbsoluteDeadline(authenticatedAtEpochSeconds, nowEpochSeconds)
}
