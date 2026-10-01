package de.missionleben.portal.auth

/** A browser callback is not a local unlock. Every personal login needs a fresh human login. */
data class PersonalLoginAttempt(
    val epoch: Long,
    val deviceId: String,
    val startedAtEpochSeconds: Long,
    val persistSession: Boolean,
    val boundSubject: String? = null,
)

data class AuthenticationEvidence(
    val issuer: String,
    val audiences: Set<String>,
    val authorizedParty: String?,
    val subject: String,
    val authenticatedAtEpochSeconds: Long,
    val hasRefreshToken: Boolean,
)

object PersonalAuthenticationPolicy {
    // Timestamp tolerance is only an extra check, never a substitute for confirmed cookie isolation.
    const val CLOCK_SKEW_SECONDS = 30L

    fun permitsFreshLogin(
        attempt: PersonalLoginAttempt,
        evidence: AuthenticationEvidence,
        expectedIssuer: String,
        clientId: String,
        currentEpoch: Long,
        currentDeviceId: String?,
        deviceTrusted: Boolean,
        contextCleared: Boolean,
        locallyLocked: Boolean,
        nowEpochSeconds: Long,
    ): Boolean =
        contextCleared && !locallyLocked && deviceTrusted &&
            attempt.epoch == currentEpoch && attempt.deviceId.isNotBlank() &&
            attempt.deviceId == currentDeviceId && attempt.startedAtEpochSeconds > 0L &&
            nowEpochSeconds >= attempt.startedAtEpochSeconds &&
            evidence.issuer == expectedIssuer && evidence.subject.isNotBlank() &&
            (attempt.boundSubject == null || attempt.boundSubject == evidence.subject) &&
            clientId in evidence.audiences &&
            (evidence.authorizedParty == null || evidence.authorizedParty == clientId) &&
            (evidence.audiences.size == 1 || evidence.authorizedParty == clientId) &&
            evidence.authenticatedAtEpochSeconds > 0L &&
            evidence.authenticatedAtEpochSeconds >= attempt.startedAtEpochSeconds - CLOCK_SKEW_SECONDS &&
            evidence.authenticatedAtEpochSeconds <= nowEpochSeconds + CLOCK_SKEW_SECONDS &&
            (attempt.persistSession || !evidence.hasRefreshToken)
}
