package de.missionleben.portal.push

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

object NotificationNavigation {
    fun resolve(
        action: PushAction,
        targetId: String,
        applicationLaunchUrl: String,
        zimbraWebBaseUrl: String,
    ): String {
        val validated = NotificationDetail.notificationTarget(action, targetId)
        if (validated.isBlank()) return applicationLaunchUrl
        return when (action) {
            PushAction.OPEN_MAIL -> zimbraMessageUrl(zimbraWebBaseUrl, validated)
            PushAction.OPEN_TALK -> talkRoomUrl(applicationLaunchUrl, validated)
            PushAction.OPEN_CALENDAR, PushAction.REFRESH_SECURITY_STATE -> applicationLaunchUrl
        }
    }

    internal fun zimbraMailOverviewUrl(targetUrl: String, zimbraWebBaseUrl: String): String? {
        val target = runCatching { URI(targetUrl) }.getOrNull() ?: return null
        val base = runCatching { URI(zimbraWebBaseUrl) }.getOrNull() ?: return null
        if (!target.scheme.equals("https", ignoreCase = true) || !base.scheme.equals("https", ignoreCase = true)) {
            return null
        }
        if (!target.host.equals(base.host, ignoreCase = true) || effectivePort(target) != effectivePort(base)) {
            return null
        }
        if (!target.rawPath.orEmpty().matches(Regex("/modern/email/(?:Inbox/message/[0-9]{1,20}|new)/?"))) {
            return null
        }
        return URI("https", base.rawAuthority, "/modern/email/Inbox", null, null).toASCIIString()
    }

    internal fun isZimbraMailOverviewUrl(currentUrl: String, zimbraWebBaseUrl: String): Boolean {
        val current = runCatching { URI(currentUrl) }.getOrNull() ?: return false
        val base = runCatching { URI(zimbraWebBaseUrl) }.getOrNull() ?: return false
        if (!current.scheme.equals("https", ignoreCase = true) || !base.scheme.equals("https", ignoreCase = true)) {
            return false
        }
        if (!current.host.equals(base.host, ignoreCase = true) || effectivePort(current) != effectivePort(base)) {
            return false
        }
        val path = current.rawPath.orEmpty()
        return path.trimEnd('/') != "/modern/email/new" && path.matches(Regex("/modern/email(?:/[^/]+)?/?"))
    }

    private fun zimbraMessageUrl(baseUrl: String, messageId: String): String {
        val base = runCatching { URI(baseUrl) }.getOrNull() ?: return baseUrl
        if (!base.scheme.equals("https", ignoreCase = true) || base.host.isNullOrBlank()) return baseUrl
        // Delegated Zimbra admin searches qualify an item as
        // "<account-uuid>:<message-id>".  The authenticated user's Modern UI
        // expects the mailbox-local numeric ID in its route.
        val localMessageId = messageId.substringAfterLast(':')
        return URI(
            "https",
            base.rawAuthority,
            "/modern/email/Inbox/message/$localMessageId",
            null,
            null,
        ).toASCIIString()
    }

    private fun talkRoomUrl(launchUrl: String, roomToken: String): String {
        val launch = runCatching { URI(launchUrl) }.getOrNull() ?: return launchUrl
        if (!launch.scheme.equals("https", ignoreCase = true) || launch.host.isNullOrBlank()) return launchUrl
        val redirectPath = if (
            launch.path.orEmpty().contains("/index.php/") ||
            decodedQuery(launch.rawQuery).any { it.first == "redirectUrl" && it.second.contains("/index.php/") }
        ) {
            "/index.php/call/$roomToken"
        } else {
            "/call/$roomToken"
        }
        val directRoom = URI("https", launch.rawAuthority, redirectPath, null, null).toASCIIString()
        if (!launch.path.orEmpty().contains("/apps/user_oidc/login/")) return directRoom

        val parameters = decodedQuery(launch.rawQuery)
            .filterNot { it.first == "redirectUrl" }
            .plus("redirectUrl" to directRoom)
            .joinToString("&") { (name, value) -> "${encode(name)}=${encode(value)}" }
        val endpoint = URI("https", launch.rawAuthority, launch.rawPath, null, null).toASCIIString()
        return "$endpoint?$parameters"
    }

    private fun decodedQuery(query: String?): List<Pair<String, String>> = query
        .orEmpty()
        .split('&')
        .filter(String::isNotBlank)
        .map { parameter ->
            val parts = parameter.split('=', limit = 2)
            decode(parts[0]) to decode(parts.getOrElse(1) { "" })
        }

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)
        .replace("+", "%20")

    private fun decode(value: String): String = runCatching {
        URLDecoder.decode(value, StandardCharsets.UTF_8)
    }.getOrDefault(value)

    private fun effectivePort(uri: URI): Int = when {
        uri.port >= 0 -> uri.port
        uri.scheme.equals("https", ignoreCase = true) -> 443
        else -> -1
    }
}
