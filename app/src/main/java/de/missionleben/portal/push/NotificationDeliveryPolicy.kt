package de.missionleben.portal.push

object NotificationDeliveryPolicy {
    fun validMinuteOfDay(value: Int): Boolean = value in 0..1439

    fun shouldDeliver(
        communicationEnabled: Boolean,
        quietHoursEnabled: Boolean,
        quietStartMinutes: Int,
        quietEndMinutes: Int,
        currentMinutes: Int,
    ): Boolean {
        if (!communicationEnabled) return false
        if (!quietHoursEnabled) return true
        if (
            !validMinuteOfDay(quietStartMinutes) ||
            !validMinuteOfDay(quietEndMinutes) ||
            !validMinuteOfDay(currentMinutes) ||
            quietStartMinutes == quietEndMinutes
        ) {
            return true
        }
        val quiet = if (quietStartMinutes < quietEndMinutes) {
            currentMinutes in quietStartMinutes until quietEndMinutes
        } else {
            currentMinutes >= quietStartMinutes || currentMinutes < quietEndMinutes
        }
        return !quiet
    }
}
