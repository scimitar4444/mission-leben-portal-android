package de.missionleben.portal.web

import org.junit.Assert.assertEquals
import org.junit.Test

class CookieClearanceGateTest {
    private val results = mutableListOf<Boolean>()
    private val gate = CookieClearanceGate { results += it }
    @Test fun noLoadBeforeAcknowledgement() { assertEquals(emptyList<Boolean>(), results) }
    @Test fun acknowledgedEmptyJarSucceedsOnce() {
        gate.acknowledged(false); gate.failed(); gate.acknowledged(false)
        assertEquals(listOf(true), results)
    }
    @Test fun remainingCookiesAreFailure() {
        gate.acknowledged(true); assertEquals(listOf(false), results)
    }
    @Test fun timeoutCannotBecomeOptimisticSuccess() {
        gate.failed(); gate.acknowledged(false); assertEquals(listOf(false), results)
    }
    @Test fun removalExceptionOrCancellationFailsOnce() {
        gate.failed(); gate.failed(); assertEquals(listOf(false), results)
    }
}
