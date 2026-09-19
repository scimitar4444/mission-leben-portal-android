package de.missionleben.portal.auth

internal object IdentityDisplayName {
    fun select(
        givenName: String?,
        fullName: String?,
        preferredUsername: String?,
        email: String?,
        fallback: String,
    ): String = sequenceOf(givenName, fullName, preferredUsername, email)
        .map { it.orEmpty().trim() }
        .firstOrNull(String::isNotBlank)
        ?: fallback
}
