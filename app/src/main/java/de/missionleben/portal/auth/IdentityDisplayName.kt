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
        if (explicitGivenName.isNotBlank()) return firstNameFrom(explicitGivenName)

        val normalizedFullName = fullName.orEmpty().trim()
        if (normalizedFullName.isNotBlank()) {
            val likelyGivenName = firstNameFrom(normalizedFullName)
            if (likelyGivenName.isNotBlank()) return likelyGivenName
        }

        return sequenceOf(preferredUsername, email)
            .map { it.orEmpty().trim() }
            .firstOrNull(String::isNotBlank)
            ?: fallback
    }

    private fun firstNameFrom(value: String): String = if (',' in value) {
        value.substringAfter(',').trim()
    } else {
        value.trim()
    }.substringBefore(' ').trim()
}
