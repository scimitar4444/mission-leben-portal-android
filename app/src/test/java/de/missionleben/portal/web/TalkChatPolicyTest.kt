package de.missionleben.portal.web

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TalkChatPolicyTest {
    @Test
    fun `recognizes trusted Talk pages`() {
        assertTrue(TalkChatPolicy.isTalkPage("https://nextcloud.mission-leben.de/apps/spreed/"))
        assertTrue(TalkChatPolicy.isTalkPage("https://nextcloud.mission-leben.de/index.php/apps/spreed/"))
        assertTrue(TalkChatPolicy.isTalkPage("https://nextcloud.mission-leben.de/call/9ctuo3hw"))
        assertTrue(TalkChatPolicy.isTalkPage("https://nextcloud.mission-leben.de/index.php/call/Abc1234"))
    }

    @Test
    fun `does not restrict unrelated or untrusted pages`() {
        assertFalse(TalkChatPolicy.isTalkPage("https://nextcloud.mission-leben.de/apps/files/"))
        assertFalse(TalkChatPolicy.isTalkPage("https://evil.example/apps/spreed/"))
        assertFalse(TalkChatPolicy.isTalkPage("http://nextcloud.mission-leben.de/apps/spreed/"))
    }

    @Test
    fun `chat only script targets semantic Talk call controls`() {
        val script = TalkChatPolicy.CHAT_ONLY_SCRIPT

        assertTrue(script.contains(".join-call"))
        assertTrue(script.contains(".leave-call"))
        assertTrue(script.contains(".call-button"))
        assertTrue(script.contains(".talk-dashboard__actions button:has(.video-outline-icon)"))
        assertTrue(script.contains("button:has(.microphone-outline-icon)"))
        assertTrue(script.contains(".event-section"))
        assertTrue(script.contains(".instant-meeting__dialog"))
        assertTrue(script.contains(".app-navigation-toggle"))
        assertTrue(script.contains("openConversationNavigation"))
        assertTrue(script.contains("lockRestrictedControls"))
        assertTrue(script.contains("element.disabled = true"))
        assertTrue(script.contains("document.addEventListener('touchstart'"))
        assertTrue(script.contains("documentObserver.observe(document"))
        assertTrue(script.contains("dismissUnsupportedBrowserWarning"))
        assertTrue(script.contains("message.includes('Nextcloud Talk')"))
        assertTrue(script.contains("message.includes('Mozilla Firefox')"))
        assertTrue(script.contains("ml-talk-last-room-path"))
        assertTrue(script.contains("rememberOrRestoreLastRoom"))
        assertTrue(script.contains("roomPathPattern.test(lastRoomPath)"))
        assertTrue(script.contains("window.location.replace(window.location.origin + lastRoomPath)"))
        assertTrue(script.contains("window.localStorage.removeItem(lastRoomStorageKey)"))
        assertTrue(script.contains("'#header'"))
        assertTrue(script.contains("html[data-ml-talk-chat-only] #content"))
        assertTrue(script.contains("top:0 !important"))
        assertTrue(script.contains("margin-top:0 !important"))
        assertTrue(script.contains("bottom:0 !important"))
        assertTrue(script.contains("height:auto !important"))
        assertTrue(script.contains("html[data-ml-talk-chat-only] #content-vue"))
        assertTrue(script.contains("height:100% !important"))
        assertTrue(script.contains("data-ml-talk-chat-only"))
        assertTrue(script.contains("stopImmediatePropagation"))
        assertFalse(script.contains("Besprechung beginnen"))
        assertFalse(script.contains("Start call"))
    }
}
