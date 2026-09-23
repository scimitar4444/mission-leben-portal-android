package de.missionleben.portal.push

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

/** Mailbox-local invitation ID plus the exact occurrence; never a remote URL. */
data class CalendarNotificationTarget(
    val inviteId: String,
    val recurrenceId: String,
    val startMillis: Long,
    val endMillis: Long,
) {
    companion object {
        private val pattern = Regex("([0-9]{1,20}-[0-9]{1,20})\\|([0-9]{8}(?:T[0-9]{6}Z)?|)\\|([0-9]{13})\\|([0-9]{13})")
        private val recurrenceFormat = DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss'Z'")
            .withResolverStyle(ResolverStyle.STRICT)

        fun parse(value: String): CalendarNotificationTarget? {
            val match = pattern.matchEntire(value) ?: return null
            val (id, recurrence, start, end) = match.destructured
            val startMillis = start.toLongOrNull() ?: return null
            val endMillis = end.toLongOrNull() ?: return null
            if (endMillis <= startMillis) return null
            if (recurrence.isNotEmpty() && runCatching {
                    if (recurrence.length == 8) LocalDate.parse(recurrence, DateTimeFormatter.BASIC_ISO_DATE)
                    else LocalDateTime.parse(recurrence, recurrenceFormat)
                }.isFailure
            ) return null
            return CalendarNotificationTarget(id, recurrence, startMillis, endMillis)
        }
    }
}
