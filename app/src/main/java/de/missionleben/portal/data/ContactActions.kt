package de.missionleben.portal.data

/** Only telephone numbers, never dialler service codes, URI schemes or pauses. */
internal object ContactActions {
    fun dialable(value: String): String? {
        if (!Regex("\\+?[0-9 ()/.-]{3,60}").matches(value)) return null
        return value.filter { it in '0'..'9' || it == '+' }.takeIf { it.count(Char::isDigit) >= 3 }
    }
}
