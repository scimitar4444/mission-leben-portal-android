package de.missionleben.portal.web

import org.junit.Assert.*
import org.junit.Test

class TalkVoicePolicyTest {
    private val origin = "https://nextcloud.mission-leben.de"
    private val audio = listOf(TalkVoicePolicy.AUDIO_CAPTURE)

    @Test fun onlyExactHttpsTalkDocumentsAndOriginsCanUseAudio() {
        for (page in listOf("/apps/spreed", "/apps/spreed/", "/apps/spreed/not-found",
            "/index.php/apps/spreed/", "/call/abcd_123", "/index.php/call/abcd_123/")) {
            assertTrue(page, TalkVoicePolicy.allows(origin + page, origin, audio))
            assertTrue(page, TalkVoicePolicy.allows(origin + page, "$origin:443/", audio))
        }
    }

    @Test fun noOtherAppHostPortSchemeCredentialsOrLookalikePath() {
        for (page in listOf("http://nextcloud.mission-leben.de/call/abcd", "$origin:8443/call/abcd",
            "https://evil.example/call/abcd", "https://nextcloud.mission-leben.de.evil.example/call/abcd",
            "https://user@nextcloud.mission-leben.de/call/abcd", "$origin/apps/files/",
            "$origin/apps/spreed-other/", "$origin/elsewhere/apps/spreed/", "$origin/elsewhere/call/abcd",
            "$origin/call/%61bcd", "$origin/call/../files", "$origin/call/a", "not a URL")) {
            assertFalse(page, TalkVoicePolicy.allows(page, origin, audio))
        }
        for (source in listOf("http://nextcloud.mission-leben.de", "$origin:8443", "$origin/apps/spreed/",
            "$origin?x=1", "$origin#x", "https://user@nextcloud.mission-leben.de",
            "https://evil.example", "https://nextcloud.mission-leben.de.evil.example")) {
            assertFalse(source, TalkVoicePolicy.allows("$origin/call/abcd", source, audio))
        }
    }

    @Test fun cameraMixedEmptyDuplicateAndUnknownResourcesAreDenied() {
        for (resources in listOf(emptyList(), listOf("android.webkit.resource.VIDEO_CAPTURE"),
            audio + "android.webkit.resource.VIDEO_CAPTURE", audio + "future-resource", audio + audio,
            listOf("android.webkit.resource.MIDI_SYSEX"))) {
            assertFalse(resources.toString(), TalkVoicePolicy.allows("$origin/call/abcd", origin, resources))
        }
    }
}
