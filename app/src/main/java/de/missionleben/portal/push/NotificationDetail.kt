package de.missionleben.portal.push

import org.json.JSONObject
import java.time.Instant
import java.time.OffsetDateTime

data class NotificationDetail(
    val eventId: String,
    val action: PushAction,
    val title: String,
    val summary: String,
    val preview: String,
    val displayAtMillis: Long?,
    val expiresAtMillis: Long?,
) {
    fun isExpired(nowMillis: Long = System.currentTimeMillis()): Boolean =
        expiresAtMillis?.let { it <= nowMillis } ?: false

    companion object {
        fun fromJson(value: JSONObject): NotificationDetail {
            val eventId = value.getString("event_id")
            require(PushCommand.validEventId(eventId)) { "Ungültige Ereignis-ID." }
            val action = PushAction.fromWireName(value.getString("event_type"))
                ?.takeUnless { it == PushAction.REFRESH_SECURITY_STATE }
                ?: error("Unbekannter Benachrichtigungstyp.")
            return NotificationDetail(
                eventId = eventId,
                action = action,
                title = sanitize(value.optString("title"), 80),
                summary = sanitize(value.optString("summary"), 160),
                preview = sanitize(value.optString("preview"), 280),
                displayAtMillis = parseTime(value.optString("display_at")),
                expiresAtMillis = parseTime(value.optString("expires_at")),
            )
        }

        internal fun sanitize(value: String, maximumLength: Int): String = value
            .replace(Regex("[\\p{Cc}\\p{Cf}]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(maximumLength)

        private fun parseTime(value: String): Long? {
            if (value.isBlank()) return null
            return runCatching { Instant.parse(value).toEpochMilli() }
                .recoverCatching { OffsetDateTime.parse(value).toInstant().toEpochMilli() }
                .getOrNull()
        }
    }
}
