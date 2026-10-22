package de.missionleben.portal.web

import org.junit.Assert.*
import org.junit.Test

class TalkVoicePermissionGateTest {
    private class Request : TalkVoiceRequest {
        override val identity = Any()
        override val origin = "https://nextcloud.mission-leben.de"
        override val resources = listOf(TalkVoicePolicy.AUDIO_CAPTURE)
        var grants = 0
        var denials = 0
        override fun grantAudio() { grants++ }
        override fun deny() { denials++ }
    }

    private class Fixture {
        var page = "https://nextcloud.mission-leben.de/call/abcd_123"
        var contextAllowed = true
        var foreground = true
        var permission = false
        var asks = 0
        var hints = 0
        var launchFails = false
        val gate = TalkVoicePermissionGate({ page }, { contextAllowed }, { foreground }, { permission },
            { asks++; if (launchFails) error("Permission launcher unavailable") }, { hints++ })
        fun result(granted: Boolean) { permission = granted; gate.androidPermissionResult(granted) }
    }

    @Test fun firstRequestNeedsAndroidConsentAndCachedConsentStaysScoped() {
        val f = Fixture(); val first = Request()
        f.gate.request(first)
        assertEquals(1, f.asks); assertEquals(0, first.grants)
        f.result(true)
        assertEquals(1, first.grants); assertEquals(0, first.denials)
        val next = Request(); f.gate.request(next)
        assertEquals(1, next.grants); assertEquals(1, f.asks)
    }

    @Test fun permissionDialogPauseAndEarlyResultWaitForResume() {
        val f = Fixture(); val request = Request(); f.gate.request(request)
        f.foreground = false; f.gate.pause(); f.result(true)
        assertEquals(0, request.grants); assertEquals(0, request.denials)
        f.foreground = true; f.gate.resume()
        assertEquals(1, request.grants)
    }

    @Test fun permissionDeniedKeepsTextUsableAndNeverGrantsLater() {
        val f = Fixture(); val request = Request(); f.gate.request(request); f.result(false)
        assertEquals(1, request.denials); assertEquals(1, f.hints)
        f.permission = true; f.gate.resume()
        assertEquals(0, request.grants)
    }

    @Test fun navigatingEvenToAnotherAllowedRoomInvalidatesThePendingRequest() {
        val f = Fixture(); val request = Request(); f.gate.request(request)
        f.page = "https://nextcloud.mission-leben.de/call/another_room"; f.result(true)
        assertEquals(0, request.grants); assertEquals(1, request.denials)
    }

    @Test fun lockingOrRevokingSessionBeforeAndroidResultDeniesIt() {
        val f = Fixture(); val request = Request(); f.gate.request(request)
        f.contextAllowed = false; f.result(true)
        assertEquals(0, request.grants); assertEquals(1, request.denials)
    }

    @Test fun backgroundAndNonApplicationContextsDoNotAskForMicrophone() {
        for (background in listOf(true, false)) {
            val f = Fixture(); val request = Request(); f.permission = true
            if (background) f.foreground = false else f.contextAllowed = false
            f.gate.request(request)
            assertEquals(0, request.grants); assertEquals(1, request.denials); assertEquals(0, f.asks)
        }
    }

    @Test fun competingWebsiteRequestCannotStealTheFirstPermissionResult() {
        val f = Fixture(); val first = Request(); val second = Request()
        f.gate.request(first); f.gate.request(second); f.result(true)
        assertEquals(1, first.grants); assertEquals(0, second.grants); assertEquals(1, second.denials)
    }

    @Test fun stoppedOrDestroyedRequestCannotBeRetargetedByLateAndroidResult() {
        val f = Fixture(); val first = Request(); val second = Request()
        f.gate.request(first); f.gate.cancel(); f.gate.request(second)
        assertEquals(1, second.denials); assertEquals(1, f.asks)
        f.result(true)
        assertEquals(0, first.grants); assertEquals(1, first.denials); assertEquals(0, second.grants)
        val third = Request(); f.gate.request(third)
        assertEquals(1, third.grants)
    }

    @Test fun canceledWebRequestGetsNoGrantOrDuplicateDeny() {
        val f = Fixture(); val request = Request(); f.gate.request(request)
        f.gate.canceled(request.identity); f.result(true); f.gate.resume()
        assertEquals(0, request.grants); assertEquals(0, request.denials)
    }

    @Test fun unrelatedCancellationDoesNotCancelPendingRequest() {
        val f = Fixture(); val request = Request(); f.gate.request(request)
        f.gate.canceled(Any()); f.result(true)
        assertEquals(1, request.grants)
    }

    @Test fun revokedAndroidPermissionBeforeResumeIsRechecked() {
        val f = Fixture(); val request = Request(); f.gate.request(request)
        f.foreground = false; f.result(true); f.permission = false; f.foreground = true; f.gate.resume()
        assertEquals(0, request.grants); assertEquals(1, request.denials)
    }

    @Test fun permissionLauncherFailureFailsClosed() {
        val f = Fixture(); val request = Request(); f.launchFails = true; f.gate.request(request)
        assertEquals(1, request.denials); assertEquals(0, request.grants); assertFalse(f.gate.androidPromptOpen)
    }
}
