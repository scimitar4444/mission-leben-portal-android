package de.missionleben.portal.auth

import de.missionleben.portal.model.DeviceMode
import de.missionleben.portal.model.EnrollmentState

data class ReauthenticationRequest(
    val loginHint: String?,
    val forceLogin: Boolean,
)

object ReauthenticationPolicy {
    const val SESSION_LIFETIME_DAYS = 90L
    private const val SESSION_LIFETIME_SECONDS = SESSION_LIFETIME_DAYS * 24L * 60L * 60L

    fun request(
        mode: DeviceMode?,
        enrollmentState: EnrollmentState,
        reauthenticationRequired: Boolean,
        storedLoginHint: String?,
    ): ReauthenticationRequest {
        val permitted = mode == DeviceMode.PERSONAL &&
            enrollmentState == EnrollmentState.TRUSTED &&
            reauthenticationRequired &&
            !storedLoginHint.isNullOrBlank()
        return ReauthenticationRequest(
            loginHint = storedLoginHint?.trim().takeIf { permitted },
            forceLogin = permitted,
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
