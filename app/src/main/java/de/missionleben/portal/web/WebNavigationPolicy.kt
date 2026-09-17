package de.missionleben.portal.web

import java.net.URI

class WebNavigationPolicy(
    allowedHostSuffixes: String,
    private val redirectUri: String,
) {
    private val suffixes = allowedHostSuffixes
        .split(',')
        .map { it.trim().trimStart('.').lowercase() }
        .filter { it.isNotBlank() }
        .toSet()

    fun isAuthorizationRedirect(url: String): Boolean =
        url.substringBefore('?').substringBefore('#') ==
            redirectUri.substringBefore('?').substringBefore('#')

    fun isTrustedWebUrl(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        if (!uri.scheme.equals("https", ignoreCase = true)) return false
        val host = uri.host?.lowercase()?.trimEnd('.') ?: return false
        return suffixes.any { suffix -> host == suffix || host.endsWith(".$suffix") }
    }

    fun canOpenExternally(url: String): Boolean = when (runCatching { URI(url).scheme?.lowercase() }.getOrNull()) {
        "https", "mailto", "tel" -> true
        else -> false
    }
}
