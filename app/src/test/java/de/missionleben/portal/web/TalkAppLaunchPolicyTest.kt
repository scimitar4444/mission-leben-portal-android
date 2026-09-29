package de.missionleben.portal.web

import org.junit.Assert.assertEquals
import org.junit.Test

class TalkAppLaunchPolicyTest {
    @Test fun `Chrome is used only if the native app is absent`() {
        assertEquals(TalkAppTarget.NATIVE_APP, TalkAppLaunchPolicy.target(talkInstalled = true))
        assertEquals(TalkAppTarget.CHROME, TalkAppLaunchPolicy.target(talkInstalled = false))
    }
}
