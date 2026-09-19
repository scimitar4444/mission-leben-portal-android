package de.missionleben.portal.web

import java.net.URI

class WebSessionPolicy(
    authentikBaseUrl: String,
    authenticationFlowSlugs: String,
) {
    private val authentikOrigin = runCatching { URI(authentikBaseUrl) }.getOrNull()
    private val authenticationFlows = authenticationFlowSlugs
        .split(',')
        .map(String::trim)
        .filter(String::isNotBlank)
        .toSet()

    fun isInteractiveAuthentication(url: String): Boolean {
        val configured = authentikOrigin ?: return false
        val candidate = runCatching { URI(url) }.getOrNull() ?: return false
        if (!candidate.scheme.equals("https", ignoreCase = true)) return false
        if (!candidate.host.equals(configured.host, ignoreCase = true)) return false
        if (effectivePort(candidate) != effectivePort(configured)) return false
        val path = candidate.path.orEmpty()
        if (!path.startsWith("/if/flow/")) return false
        val flowSlug = path.removePrefix("/if/flow/").substringBefore('/').trim()
        return flowSlug in authenticationFlows
    }

    private fun effectivePort(uri: URI): Int = when {
        uri.port >= 0 -> uri.port
        uri.scheme.equals("https", ignoreCase = true) -> 443
        else -> -1
    }
}
