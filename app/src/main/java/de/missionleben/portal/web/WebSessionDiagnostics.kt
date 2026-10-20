package de.missionleben.portal.web

import java.net.URI

/** Closed diagnostic schema: never retain headers, cookie values, query strings or error text. */
internal object WebSessionDiagnostics {
    enum class Event {
        BROWSER_OPEN, AUTHORIZATION_RETURN, PAGE_FINISHED, PERSONAL_PAUSE,
        WEB_REAUTHENTICATION, FRESH_LOGIN_CLEAR, LOCAL_DATA_CLEAR,
        CLEAR_ACKNOWLEDGED, CLEAR_FAILED, PERSIST_STARTED, PERSIST_COMPLETED, PERSIST_FAILED,
    }
    enum class CookiePresence { PRESENT, ABSENT, UNAVAILABLE }

    const val AUTHENTIK_COOKIE_NAME = "authentik_session"

    fun cookiePresence(header: String?): CookiePresence =
        if (header.orEmpty().split(';').any {
                it.substringBefore('=').trim() == AUTHENTIK_COOKIE_NAME && it.contains('=')
            }) CookiePresence.PRESENT else CookiePresence.ABSENT

    fun line(event: Event, presence: CookiePresence, webViewVersion: String?, url: String?): String {
        val uri = runCatching { URI(url.orEmpty()) }.getOrNull()
        val host = uri?.host?.lowercase()?.takeIf { it.matches(Regex("[a-z0-9.-]{1,253}")) }
            ?: "unavailable"
        // Even paths may contain credentials (enrollment links) or personal document names.
        // Only public routing shapes are retained; no percent-decoding, userinfo or fragment.
        val path = uri?.rawPath.orEmpty().takeIf {
            it == "/" || it.matches(Regex("/if/flow/[a-z0-9-]{1,80}/?")) ||
                it in setOf("/if/user/", "/application/o/authorize/", "/otobo/customer.pl")
        } ?: "/[redacted]"
        val version = webViewVersion?.takeIf { it.matches(Regex("[0-9]+(?:\\.[0-9]+){0,3}")) }
            ?: "unavailable"
        return "web_session event=${event.name} ${AUTHENTIK_COOKIE_NAME}=${presence.name} " +
            "webview=$version host=$host path=$path"
    }
}
