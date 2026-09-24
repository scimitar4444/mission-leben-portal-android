package de.missionleben.portal.push

import android.content.Context
import de.missionleben.portal.model.PortalApplication

enum class NotificationBadgeTarget {
    ZIMBRA,
    TALK;

    companion object {
        fun fromAction(action: PushAction): NotificationBadgeTarget? = when (action) {
            PushAction.OPEN_MAIL, PushAction.OPEN_CALENDAR -> ZIMBRA
            PushAction.OPEN_TALK -> TALK
            PushAction.REFRESH_SECURITY_STATE -> null
        }

        fun fromApplication(application: PortalApplication): NotificationBadgeTarget? {
            val marker = listOf(
                application.slug,
                application.name,
                application.launchUrl,
            ).joinToString(" ").lowercase()
            return fromMarker(marker)
        }

        fun fromSerialized(value: String?): NotificationBadgeTarget? =
            value?.let { serialized -> entries.firstOrNull { it.name == serialized } }

        private fun fromMarker(marker: String): NotificationBadgeTarget? {
            return when {
                "zimbra" in marker -> ZIMBRA
                "talk" in marker || "/apps/spreed" in marker -> TALK
                else -> null
            }
        }
    }
}

data class NotificationBadgeCounts(
    val zimbra: Int = 0,
    val talk: Int = 0,
) {
    fun countFor(application: PortalApplication): Int = when (
        NotificationBadgeTarget.fromApplication(application)
    ) {
        NotificationBadgeTarget.ZIMBRA -> zimbra
        NotificationBadgeTarget.TALK -> talk
        null -> 0
    }
}

class UnreadNotificationStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    @Synchronized
    fun record(action: PushAction, eventId: String): Boolean {
        val target = NotificationBadgeTarget.fromAction(action) ?: return false
        if (eventId.isBlank()) return false
        val seen = recentSeen()
        if (eventId in seen) return false
        val unread = unread(target).toMutableSet()
        unread += eventId
        val updatedSeen = (seen + eventId).takeLast(MAX_SEEN_EVENTS)
        return preferences.edit()
            .putString(unreadKey(target), unread.joinToString("\n"))
            .putString(KEY_SEEN_EVENTS, updatedSeen.joinToString("\n"))
            .commit()
    }

    @Synchronized
    fun cancel(eventId: String): Boolean {
        if (eventId.isBlank()) return false
        var changed = false
        val editor = preferences.edit()
        NotificationBadgeTarget.entries.forEach { target ->
            val unread = unread(target).toMutableSet()
            if (unread.remove(eventId)) {
                changed = true
                editor.putString(unreadKey(target), unread.joinToString("\n"))
            }
        }
        return changed && editor.commit()
    }

    @Synchronized
    fun cancel(action: PushAction, eventId: String): Boolean {
        val target = NotificationBadgeTarget.fromAction(action) ?: return false
        if (!PushCommand.validEventId(eventId)) return false
        val unread = unread(target).toMutableSet()
        if (!unread.remove(eventId)) return false
        return preferences.edit().putString(unreadKey(target), unread.joinToString("\n")).commit()
    }

    @Synchronized
    fun clear(target: NotificationBadgeTarget): Boolean {
        if (unread(target).isEmpty()) return false
        return preferences.edit().remove(unreadKey(target)).commit()
    }

    @Synchronized
    fun clearAll() {
        preferences.edit().clear().apply()
    }

    fun counts(): NotificationBadgeCounts = NotificationBadgeCounts(
        zimbra = unread(NotificationBadgeTarget.ZIMBRA).size,
        talk = unread(NotificationBadgeTarget.TALK).size,
    )

    fun isUnread(action: PushAction, eventId: String): Boolean =
        NotificationBadgeTarget.fromAction(action)?.let { eventId in unread(it) } == true

    internal fun eventIds(target: NotificationBadgeTarget): Set<String> = unread(target)

    private fun unread(target: NotificationBadgeTarget): Set<String> =
        preferences.getString(unreadKey(target), "")
            .orEmpty()
            .lineSequence()
            .filter(String::isNotBlank)
            .toSet()

    private fun recentSeen(): List<String> = preferences.getString(KEY_SEEN_EVENTS, "")
        .orEmpty()
        .lineSequence()
        .filter(String::isNotBlank)
        .toList()

    private fun unreadKey(target: NotificationBadgeTarget): String =
        KEY_UNREAD_PREFIX + target.name.lowercase()

    private companion object {
        const val PREFERENCES = "mission_leben_unread_notifications"
        const val KEY_UNREAD_PREFIX = "unread_"
        const val KEY_SEEN_EVENTS = "seen_events"
        const val MAX_SEEN_EVENTS = 512
    }
}
