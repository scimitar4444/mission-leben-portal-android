package de.missionleben.portal.security

/** In-memory only: process death already requires a fresh vault authentication. */
class PersonalSessionLockTracker(
    private val elapsedRealtimeMillis: () -> Long,
) {
    private var startedActivities = 0
    private var backgroundSinceMillis: Long? = null
    private var lockRequired = false

    fun activityStarted() {
        if (startedActivities == 0) {
            backgroundSinceMillis?.let { started ->
                val elapsed = elapsedRealtimeMillis() - started
                if (elapsed < 0 || elapsed >= BACKGROUND_TIMEOUT_MILLIS) lockRequired = true
            }
            backgroundSinceMillis = null
        }
        startedActivities++
    }

    fun activityStopped() {
        if (startedActivities == 0) return
        startedActivities--
        if (startedActivities == 0) backgroundSinceMillis = elapsedRealtimeMillis()
    }

    fun screenTurnedOff() {
        lockRequired = true
    }

    fun isLockRequired(): Boolean = lockRequired

    fun consumeLockRequired(): Boolean = lockRequired.also { lockRequired = false }

    companion object {
        const val BACKGROUND_TIMEOUT_MILLIS = 10 * 60 * 1_000L
    }
}
