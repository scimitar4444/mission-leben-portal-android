package de.missionleben.portal.push

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class InboxNotice(val eventId: String, val action: PushAction, val receivedAtMillis: Long,
    val read: Boolean = false, val targetId: String = "")

/** Identifier-only history: no titles, senders, previews or ticket bodies at rest.
 * Separate from Android's tray/unread counter; clearing a session clears both.
 */
class NoticeInboxStore(context: Context) {
    private val preferences = context.getSharedPreferences("notice_inbox", Context.MODE_PRIVATE)

    fun notices(now: Long = System.currentTimeMillis()): List<InboxNotice> = runCatching {
        val array = JSONArray(preferences.getString("events", "[]"))
        (0 until array.length()).mapNotNull { index ->
            val json = array.optJSONObject(index) ?: return@mapNotNull null
            val action = PushAction.fromWireName(json.optString("action")) ?: return@mapNotNull null
            val id = json.optString("id")
            val received = json.optLong("received")
            if (action !in ACTIONS || !PushCommand.validEventId(id) || received <= 0 ||
                received > now || now - received > RETENTION_MILLIS) return@mapNotNull null
            InboxNotice(id, action, received, json.optBoolean("read"),
                NotificationDetail.notificationTarget(action, json.optString("target")))
        }.distinctBy { it.eventId }.sortedByDescending { it.receivedAtMillis }.take(100)
    }.getOrDefault(emptyList())

    fun record(action: PushAction, eventId: String) = synchronized(LOCK) {
        if (action !in ACTIONS || !PushCommand.validEventId(eventId)) return@synchronized
        val current = notices()
        if (current.none { it.eventId == eventId }) save(listOf(InboxNotice(eventId, action, System.currentTimeMillis())) + current)
    }

    fun rememberTarget(eventId: String, action: PushAction, targetId: String) = synchronized(LOCK) {
        val validated = NotificationDetail.notificationTarget(action, targetId)
        if (validated.isBlank()) return@synchronized
        save(notices().map { if (it.eventId == eventId && it.action == action) it.copy(targetId = validated) else it })
    }

    fun markRead(eventId: String, expectedAction: PushAction? = null) = synchronized(LOCK) {
        save(notices().map { if (it.eventId == eventId && (expectedAction == null || it.action == expectedAction))
            it.copy(read = true) else it })
    }

    fun markApplicationRead(target: NotificationBadgeTarget) = synchronized(LOCK) {
        save(notices().map { if (NotificationBadgeTarget.fromAction(it.action) == target) it.copy(read = true) else it })
    }

    fun remove(eventId: String) = synchronized(LOCK) { save(notices().filterNot { it.eventId == eventId }) }
    fun clear() = synchronized(LOCK) { preferences.edit().clear().commit(); Unit }

    private fun save(notices: List<InboxNotice>) {
        val array = JSONArray()
        notices.sortedByDescending { it.receivedAtMillis }.take(100).forEach {
            array.put(JSONObject().put("id", it.eventId).put("action", it.action.wireName)
                .put("received", it.receivedAtMillis).put("read", it.read).put("target", it.targetId))
        }
        preferences.edit().putString("events", array.toString()).commit()
    }

    companion object {
        private val LOCK = Any()
        val ACTIONS = setOf(PushAction.OPEN_DOCUMENTS, PushAction.OPEN_HELPDESK)
        const val RETENTION_MILLIS = 14L * 24 * 60 * 60 * 1000
    }
}
