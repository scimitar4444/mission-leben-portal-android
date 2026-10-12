package de.missionleben.portal.model

import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

object ApplicationShortcuts {
    fun ordered(applications: List<PortalApplication>, favorites: Set<String>): List<PortalApplication> =
        applications.sortedBy { if (it.slug in favorites) 0 else 1 }

    fun applicationForUrl(applications: List<PortalApplication>, url: String): PortalApplication? {
        val target = runCatching { URI(url) }.getOrNull() ?: return null
        if (target.scheme != "https" || target.host.isNullOrBlank() || target.userInfo != null) return null
        return applications.filter { app ->
            val launch = runCatching { URI(app.launchUrl) }.getOrNull() ?: return@filter false
            launch.scheme == "https" && launch.userInfo == null && launch.host == target.host &&
                port(launch) == port(target) &&
                (target.path == launch.path || target.path.orEmpty().startsWith(launch.path.orEmpty().trimEnd('/') + "/"))
        }.maxByOrNull { URI(it.launchUrl).path.orEmpty().length }
    }

    fun problemReportUrl(helpdesk: PortalApplication, source: PortalApplication, subject: String, body: String): String? {
        if (helpdesk.slug != "otobo-mitarbeiter") return null
        val url = runCatching { URI(helpdesk.launchUrl) }.getOrNull() ?: return null
        if (url.scheme != "https" || url.host != "it-service.mission-leben.de" || url.userInfo != null ||
            url.port !in setOf(-1, 443, 8443) || url.path != "/otobo/customer.pl") return null
        if (source.slug.isBlank()) return null
        fun encode(value: String) = URLEncoder.encode(value, StandardCharsets.UTF_8)
        return "${url.scheme}://${url.rawAuthority}${url.rawPath}?Action=CustomerTicketMessage" +
            "&Subject=${encode(subject.take(160))}&Body=${encode(body.take(500))}"
    }

    private fun port(uri: URI): Int = if (uri.port == -1) 443 else uri.port
}
