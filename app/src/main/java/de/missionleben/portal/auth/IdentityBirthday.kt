package de.missionleben.portal.auth

import java.time.LocalDate
import java.time.MonthDay

internal object IdentityBirthday {
    fun normalizedMonthDay(value: String?): String? {
        val candidate = value?.trim().orEmpty()
        if (candidate.isEmpty()) return null
        return runCatching {
            val monthDay = if (candidate.startsWith("--")) {
                MonthDay.parse(candidate)
            } else {
                MonthDay.from(LocalDate.parse(candidate))
            }
            monthDay.toString()
        }.getOrNull()
    }

    fun isToday(value: String?, today: MonthDay = MonthDay.now()): Boolean =
        normalizedMonthDay(value) == today.toString()
}
