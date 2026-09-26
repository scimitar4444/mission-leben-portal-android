package de.missionleben.portal.push

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PushReliabilityPolicyTest {
    private val needsExemption = PushReliabilityStatus(true, true, false, "samsung")

    @Test fun promptsOnlyOnceForRegisteredSignedInDevice() {
        assertTrue(PushReliabilityPolicy.shouldPrompt(true, true, false, true, needsExemption))
        assertFalse(PushReliabilityPolicy.shouldPrompt(true, true, true, true, needsExemption))
        assertFalse(PushReliabilityPolicy.shouldPrompt(false, true, false, true, needsExemption))
        assertFalse(PushReliabilityPolicy.shouldPrompt(true, true, false, true, needsExemption.copy(hasSubscription = false)))
    }

    @Test fun doesNotPromptWhenAlreadyProtected() {
        assertFalse(PushReliabilityPolicy.shouldPrompt(
            true, true, false, true, needsExemption.copy(batteryExempt = true),
        ))
        assertTrue(PushReliabilityPolicy.shouldPrompt(
            true, true, false, true, needsExemption.copy(batteryExempt = true, notificationsAllowed = false),
        ))
        assertFalse(PushReliabilityPolicy.shouldPrompt(
            true, true, false, false,
            needsExemption.copy(hasSubscription = false, notificationsAllowed = false),
        ))
        assertTrue(PushReliabilityPolicy.shouldPrompt(
            true, true, false, true,
            needsExemption.copy(hasSubscription = false, notificationsAllowed = false),
        ))
    }
}
