package de.missionleben.portal.data

import android.content.Context
import java.security.MessageDigest

/** Only slugs, separated by authenticated subject. Never a source of access rights. */
class ApplicationFavoritesStore(context: Context) {
    private val preferences = context.getSharedPreferences("application_favorites", Context.MODE_PRIVATE)

    fun load(subject: String): Set<String> = if (subject.isBlank()) emptySet() else
        preferences.getStringSet(key(subject), emptySet()).orEmpty().toSet()

    fun save(subject: String, slugs: Set<String>) {
        if (subject.isBlank()) return
        preferences.edit().putStringSet(key(subject), slugs.filter { it.isNotBlank() && it.length <= 200 }.take(100).toSet()).apply()
    }

    fun clear() = preferences.edit().clear().apply()

    private fun key(subject: String): String = MessageDigest.getInstance("SHA-256")
        .digest(subject.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
}
