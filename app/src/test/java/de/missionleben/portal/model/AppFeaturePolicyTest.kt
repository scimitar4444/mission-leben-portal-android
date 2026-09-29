package de.missionleben.portal.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppFeaturePolicyTest {
    private val zimbra = PortalApplication("Mail", "zimbra-mail", "https://mail.mission-leben.de/")
    private val talk = PortalApplication("Talk", "nextcloud-talk", "https://nextcloud.mission-leben.de/apps/spreed/")

    @Test fun `only visible integrations expose matching settings`() {
        val none = AppFeaturePolicy.from(emptyList())
        assertFalse(none.communication)
        assertFalse(none.canHandoffTalk(setOf(PortalCapability.OPEN_TALK)))

        val mailOnly = AppFeaturePolicy.from(listOf(zimbra))
        assertTrue(mailOnly.zimbra)
        assertFalse(mailOnly.talk)
        assertFalse(mailOnly.canHandoffTalk(setOf(PortalCapability.OPEN_TALK)))

        val talkOnly = AppFeaturePolicy.from(listOf(talk))
        assertFalse(talkOnly.zimbra)
        assertTrue(talkOnly.talk)
        assertFalse(talkOnly.canHandoffTalk(emptySet()))
        assertTrue(talkOnly.canHandoffTalk(setOf(PortalCapability.OPEN_TALK)))

        val both = AppFeaturePolicy.from(listOf(zimbra, talk))
        assertTrue(both.zimbra && both.talk)
    }

    @Test fun `unintegrated future app grants no existing service setting`() {
        val unknown = PortalApplication("Future", "future-app", "https://example.invalid/")
        assertFalse(AppFeaturePolicy.from(listOf(unknown)).communication)
    }
}
