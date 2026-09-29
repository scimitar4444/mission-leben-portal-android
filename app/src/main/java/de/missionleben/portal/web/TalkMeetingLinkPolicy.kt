package de.missionleben.portal.web

import java.net.URI

data class TalkMeetingLink(val webUrl: String, val appUrl: String)

/** Only meeting links on our Nextcloud host may leave the chat-only WebView. */
object TalkMeetingLinkPolicy {
    private const val HOST = "nextcloud.mission-leben.de"
    private val roomPath = Regex("^/(?:index\\.php/)?call/([A-Za-z0-9]{4,32})/?$")

    fun parse(url: String): TalkMeetingLink? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", ignoreCase = true) ||
            !uri.host.equals(HOST, ignoreCase = true) ||
            uri.rawUserInfo != null || uri.port !in setOf(-1, 443)
        ) return null
        val token = roomPath.matchEntire(uri.rawPath.orEmpty())?.groupValues?.get(1) ?: return null
        return TalkMeetingLink(url, "nextcloudtalk://$HOST/call/$token")
    }
}
