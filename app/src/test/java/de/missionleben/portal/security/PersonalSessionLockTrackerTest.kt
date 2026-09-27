package de.missionleben.portal.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonalSessionLockTrackerTest {
    private var now = 1_000L
    private val tracker = PersonalSessionLockTracker { now }

    @Test fun shortBackgroundSwitchDoesNotLock() {
        tracker.activityStarted()
        tracker.activityStopped()
        now += PersonalSessionLockTracker.BACKGROUND_TIMEOUT_MILLIS - 1
        tracker.activityStarted()
        assertFalse(tracker.consumeLockRequired())
    }

    @Test fun tenMinutesInBackgroundLocks() {
        tracker.activityStarted()
        tracker.activityStopped()
        now += PersonalSessionLockTracker.BACKGROUND_TIMEOUT_MILLIS
        tracker.activityStarted()
        assertTrue(tracker.consumeLockRequired())
        assertFalse(tracker.consumeLockRequired())
    }

    @Test fun switchingBetweenAppActivitiesDoesNotStartTimeout() {
        tracker.activityStarted()
        tracker.activityStarted()
        tracker.activityStopped()
        now += PersonalSessionLockTracker.BACKGROUND_TIMEOUT_MILLIS * 2
        assertFalse(tracker.isLockRequired())
        tracker.activityStopped()
        now += 1_000L
        tracker.activityStarted()
        assertFalse(tracker.consumeLockRequired())
    }

    @Test fun screenOffLocksImmediatelyEvenDuringShortSwitch() {
        tracker.activityStarted()
        tracker.activityStopped()
        tracker.screenTurnedOff()
        now += 1_000L
        tracker.activityStarted()
        assertTrue(tracker.consumeLockRequired())
    }

    @Test fun elapsedClockGoingBackFailsClosed() {
        tracker.activityStarted()
        tracker.activityStopped()
        now = 0L
        tracker.activityStarted()
        assertTrue(tracker.consumeLockRequired())
    }
}
