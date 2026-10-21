package de.missionleben.portal.web

import java.net.URI

/** A website microphone exception, not a general WebView media permission. */
internal object TalkVoicePolicy {
    const val AUDIO_CAPTURE = "android.webkit.resource.AUDIO_CAPTURE"
    private val talkPath = Regex("^/(?:index\\.php/)?(?:apps/spreed(?:/.*)?|call/[A-Za-z0-9_-]{4,128}/?)$")

    private fun trustedUri(value: String): URI? = runCatching { URI(value) }.getOrNull()?.takeIf {
        it.scheme.equals("https", true) && it.host.equals("nextcloud.mission-leben.de", true) &&
            it.port in listOf(-1, 443) && it.rawUserInfo == null
    }

    fun isTalkDocument(value: String): Boolean = trustedUri(value)?.let {
        talkPath.matches(it.rawPath.orEmpty())
    } == true

    fun isNextcloudPage(value: String): Boolean = trustedUri(value) != null

    fun allows(page: String, origin: String, resources: List<String>): Boolean {
        val source = trustedUri(origin) ?: return false
        return isTalkDocument(page) && source.rawPath.orEmpty() in listOf("", "/") &&
            source.rawQuery == null && source.rawFragment == null && resources == listOf(AUDIO_CAPTURE)
    }

    // Installed before Talk's application code. No audio file or background service is added.
    const val CAPTURE_GUARD_SCRIPT = """
        (() => {
          if (window.__mlTalkVoiceGuardInstalled) return true;
          const media = navigator.mediaDevices;
          if (!media || typeof media.getUserMedia !== 'function') return false;
          const nativeGetUserMedia = media.getUserMedia.bind(media);
          const streams = new Set();
          let generation = 0;
          let enabled = true;
          const allowedPage = () => /^\/(?:index\.php\/)?(?:apps\/spreed(?:\/.*)?|call\/[A-Za-z0-9_-]{4,128}\/?)${'$'}/.test(window.location.pathname);
          const stop = () => {
            enabled = false;
            generation++;
            // Talk's stop/check button sends immediately. Use its cancel control first.
            document.querySelectorAll('.audio-recorder button:has(.close-icon)').forEach(button => button.click());
            streams.forEach(stream => stream.getTracks().forEach(track => track.stop()));
            streams.clear();
          };
          window.__mlStopTalkVoiceCapture = stop;
          window.__mlResumeTalkVoiceCapture = () => { enabled = true; };
          media.getUserMedia = async constraints => {
            if (!enabled || window.top !== window || document.hidden || !allowedPage() ||
                !constraints || !constraints.audio || constraints.video) {
              throw new DOMException('Only foreground Talk voice messages are allowed', 'NotAllowedError');
            }
            const started = generation;
            const stream = await nativeGetUserMedia(constraints);
            if (!enabled || started !== generation || document.hidden || !allowedPage()) {
              stream.getTracks().forEach(track => track.stop());
              throw new DOMException('The recording context is no longer active', 'NotAllowedError');
            }
            streams.add(stream);
            stream.getTracks().forEach(track => track.addEventListener('ended', () => streams.delete(stream)));
            return stream;
          };
          document.addEventListener('visibilitychange', () => { if (document.hidden) stop(); });
          window.addEventListener('pagehide', stop);
          window.addEventListener('popstate', () => { if (!allowedPage()) stop(); });
          window.addEventListener('hashchange', () => { if (!allowedPage()) stop(); });
          window.__mlTalkVoiceGuardInstalled = true;
          return true;
        })();
    """

    const val STOP_CAPTURE_SCRIPT = "window.__mlStopTalkVoiceCapture?.();"
    const val RESUME_CAPTURE_SCRIPT = "window.__mlResumeTalkVoiceCapture?.();"
}
