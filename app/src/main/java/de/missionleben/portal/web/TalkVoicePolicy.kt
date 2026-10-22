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
          // Fixed event codes only: never include audio, identity, URL or exception text.
          const report = code => console.info('ML_TALK_VOICE:' + code);
          const allowedPage = () => /^\/(?:index\.php\/)?(?:apps\/spreed(?:\/.*)?|call\/[A-Za-z0-9_-]{4,128}\/?)${'$'}/.test(window.location.pathname);
          const stop = () => {
            report('stopped');
            enabled = false;
            generation++;
            // Talk's stop/check button sends immediately. Use its cancel control first.
            document.querySelectorAll('.audio-recorder button:has(.close-icon)').forEach(button => button.click());
            streams.forEach(stream => stream.getTracks().forEach(track => track.stop()));
            streams.clear();
          };
          window.__mlStopTalkVoiceCapture = stop;
          window.__mlResumeTalkVoiceCapture = () => { enabled = true; report('resumed'); };
          media.getUserMedia = async constraints => {
            const blocked = !enabled ? 'blocked-disabled' : window.top !== window ? 'blocked-frame'
              : document.hidden ? 'blocked-hidden' : !allowedPage() ? 'blocked-page'
              : (!constraints || !constraints.audio || constraints.video) ? 'blocked-constraints' : null;
            if (blocked) {
              report(blocked);
              throw new DOMException('Only foreground Talk voice messages are allowed', 'NotAllowedError');
            }
            report('request');
            const started = generation;
            let stream;
            try { stream = await nativeGetUserMedia(constraints); }
            catch (error) { report('native-denied'); throw error; }
            if (!enabled || started !== generation || document.hidden || !allowedPage()) {
              report('late-capture');
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
          report('installed');
          return true;
        })();
    """

    const val STOP_CAPTURE_SCRIPT = "window.__mlStopTalkVoiceCapture?.();"
    const val RESUME_CAPTURE_SCRIPT = "window.__mlResumeTalkVoiceCapture?.();"
}
