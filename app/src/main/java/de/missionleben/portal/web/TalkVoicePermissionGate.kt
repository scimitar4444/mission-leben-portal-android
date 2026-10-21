package de.missionleben.portal.web

internal interface TalkVoiceRequest {
    val identity: Any
    val origin: String
    val resources: List<String>
    fun grantAudio()
    fun deny()
}

/** Binds an asynchronous Android result to the original website request and document. */
internal class TalkVoicePermissionGate(
    private val page: () -> String,
    private val contextAllowed: () -> Boolean,
    private val foreground: () -> Boolean,
    private val hasAudioPermission: () -> Boolean,
    private val requestAndroidPermission: () -> Unit,
    private val deniedByUser: () -> Unit,
) {
    private var pending: TalkVoiceRequest? = null
    private var requestPage = ""
    var androidPromptOpen = false
        private set

    fun request(request: TalkVoiceRequest) {
        if (pending != null || androidPromptOpen || !foreground() || !allowed(request)) {
            request.deny()
            return
        }
        if (hasAudioPermission()) {
            request.grantAudio()
            return
        }
        pending = request
        requestPage = page()
        androidPromptOpen = true
        try {
            requestAndroidPermission()
        } catch (_: Exception) {
            androidPromptOpen = false
            cancel()
        }
    }

    fun androidPermissionResult(granted: Boolean) {
        if (!androidPromptOpen) return
        androidPromptOpen = false
        if (!granted) {
            val showHint = pending != null
            cancel()
            if (showHint) deniedByUser()
        } else resume()
    }

    fun resume() {
        val request = pending ?: return
        if (androidPromptOpen) return
        if (page() != requestPage || !allowed(request) || !hasAudioPermission()) {
            cancel()
            return
        }
        // Permission results can arrive before the Activity is resumed/focused again.
        if (!foreground()) return
        pending = null
        requestPage = ""
        request.grantAudio()
    }

    fun pause() {
        // The system permission dialog itself must not invalidate its own request.
        if (!androidPromptOpen) cancel()
    }

    fun canceled(identity: Any) {
        if (pending?.identity === identity) {
            pending = null
            requestPage = ""
        }
    }

    fun cancel() {
        val request = pending
        pending = null
        requestPage = ""
        request?.deny()
        // Keep an outstanding Android result fenced until its callback arrives.
    }

    private fun allowed(request: TalkVoiceRequest): Boolean = contextAllowed() &&
        TalkVoicePolicy.allows(page(), request.origin, request.resources)
}
