package de.missionleben.portal.push

import android.content.Context
import org.json.JSONObject

class NotificationTargetStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun put(eventId: String, action: PushAction, targetId: String) {
        if (!PushCommand.validEventId(eventId)) return
        val validated = NotificationDetail.notificationTarget(action, targetId)
        if (validated.isBlank()) return
        val value = JSONObject()
            .put("action", action.wireName)
            .put("target_id", validated)
            .put("stored_at", System.currentTimeMillis())
            .toString()
        val editor = preferences.edit()
        val oldestExcess = preferences.all
            .filterKeys { it != eventId }
            .mapNotNull { (key, serialized) ->
                val storedAt = (serialized as? String)?.let { raw ->
                    runCatching { JSONObject(raw).optLong("stored_at", 0L) }.getOrDefault(0L)
                } ?: 0L
                key to storedAt
            }
            .sortedBy { it.second }
            .take((preferences.all.size - (if (preferences.contains(eventId)) 1 else 0) - MAX_TARGETS + 1).coerceAtLeast(0))
        oldestExcess.forEach { (key, _) -> editor.remove(key) }
        editor.putString(eventId, value)
        editor.apply()
    }

    fun get(eventId: String?, expectedAction: PushAction): String {
        if (eventId == null || !PushCommand.validEventId(eventId)) return ""
        val serialized = preferences.getString(eventId, null) ?: return ""
        return runCatching {
            val value = JSONObject(serialized)
            val action = PushAction.fromWireName(value.optString("action"))
            if (action != expectedAction) return@runCatching ""
            NotificationDetail.notificationTarget(action, value.optString("target_id"))
        }.getOrDefault("")
    }

    fun remove(eventId: String) {
        if (PushCommand.validEventId(eventId)) preferences.edit().remove(eventId).apply()
    }

    private companion object {
        const val PREFERENCES = "notification_navigation_targets"
        const val MAX_TARGETS = 200
    }
}
