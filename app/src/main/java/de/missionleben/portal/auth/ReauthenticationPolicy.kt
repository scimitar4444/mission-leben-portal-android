package de.missionleben.portal.auth

import de.missionleben.portal.model.DeviceMode
import de.missionleben.portal.model.EnrollmentProfile
import de.missionleben.portal.model.EnrollmentState

data class ReauthenticationRequest(
    val loginHint: String?,
    val forceLogin: Boolean,
    val ninetyDayReauthentication: Boolean = false,
)

object ReauthenticationPolicy {
    const val SESSION_LIFETIME_DAYS = 90L
    private const val SESSION_LIFETIME_SECONDS = SESSION_LIFETIME_DAYS * 24L * 60L * 60L

    fun request(
        mode: DeviceMode?,
        enrollmentState: EnrollmentState,
        reauthenticationRequired: Boolean,
        storedLoginHint: String?,
        freshPersonalContext: Boolean = false,
        enrollmentProfile: EnrollmentProfile? = EnrollmentProfile.PERSONAL_EMPLOYEE,
        authenticatedAtEpochSeconds: Long = 0L,
        nowEpochSeconds: Long = System.currentTimeMillis() / 1_000L,
        absoluteDeadlineReauthenticationRequired: Boolean = false,
    ): ReauthenticationRequest {
        val permitted = mode == DeviceMode.PERSONAL &&
            enrollmentProfile == EnrollmentProfile.PERSONAL_EMPLOYEE &&
            enrollmentState == EnrollmentState.TRUSTED &&
            (reauthenticationRequired || freshPersonalContext) &&
            !storedLoginHint.isNullOrBlank()
        return ReauthenticationRequest(
            loginHint = storedLoginHint?.trim().takeIf { permitted },
            // In an already isolated context the installed provider can run prompt=login twice.
            forceLogin = permitted && reauthenticationRequired && !freshPersonalContext,
            // Routing only. The server must still prove the current endpoint and human factor.
            ninetyDayReauthentication = permitted && reauthenticationRequired && freshPersonalContext &&
                absoluteDeadlineReauthenticationRequired &&
                authenticatedAtEpochSeconds > 0L &&
                authenticatedAtEpochSeconds <= nowEpochSeconds &&
                hasReachedAbsoluteDeadline(authenticatedAtEpochSeconds, nowEpochSeconds),
        )
    }

    fun canOfferBoundDeviceReauthentication(
        mode: DeviceMode?,
        enrollmentState: EnrollmentState,
        loginHint: String?,
    ): Boolean = mode == DeviceMode.PERSONAL &&
        enrollmentState == EnrollmentState.TRUSTED &&
        !loginHint.isNullOrBlank()

    fun hasReachedAbsoluteDeadline(
        authenticatedAtEpochSeconds: Long,
        nowEpochSeconds: Long = System.currentTimeMillis() / 1_000L,
    ): Boolean = authenticatedAtEpochSeconds <= 0L ||
        nowEpochSeconds >= authenticatedAtEpochSeconds + SESSION_LIFETIME_SECONDS

    fun remainingDays(
        authenticatedAtEpochSeconds: Long,
        nowEpochSeconds: Long = System.currentTimeMillis() / 1_000L,
    ): Long {
        if (authenticatedAtEpochSeconds <= 0L) return 0L
        val remainingSeconds = authenticatedAtEpochSeconds + SESSION_LIFETIME_SECONDS - nowEpochSeconds
        if (remainingSeconds <= 0L) return 0L
        return (remainingSeconds + SECONDS_PER_DAY - 1L) / SECONDS_PER_DAY
    }

    private const val SECONDS_PER_DAY = 24L * 60L * 60L
}
