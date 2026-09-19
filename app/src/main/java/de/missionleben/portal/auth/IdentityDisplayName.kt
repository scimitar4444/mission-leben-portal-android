package de.missionleben.portal.auth

internal object IdentityDisplayName {
    fun select(
        givenName: String?,
        fullName: String?,
        preferredUsername: String?,
        email: String?,
        fallback: String,
    ): String {
        val explicitGivenName = givenName.orEmpty().trim()
        if (explicitGivenName.isNotBlank()) return explicitGivenName

        val normalizedFullName = fullName.orEmpty().trim()
        if (normalizedFullName.isNotBlank()) {
            val likelyGivenName = if (',' in normalizedFullName) {
                normalizedFullName.substringAfter(',').trim()
            } else {
                normalizedFullName
            }.substringBefore(' ').trim()
            if (likelyGivenName.isNotBlank()) return likelyGivenName
        }

        return sequenceOf(preferredUsername, email)
            .map { it.orEmpty().trim() }
            .firstOrNull(String::isNotBlank)
            ?: fallback
    }
}
