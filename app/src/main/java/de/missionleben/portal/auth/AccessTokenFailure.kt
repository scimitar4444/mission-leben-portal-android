package de.missionleben.portal.auth

data class AccessTokenFailure(
    val message: String,
    val reauthenticationRequired: Boolean,
)

internal object TokenRefreshFailurePolicy {
    fun requiresReauthentication(
        oauthError: String?,
        stateAuthorized: Boolean,
    ): Boolean = !stateAuthorized || oauthError in setOf("invalid_grant", "invalid_token")
}
