package de.missionleben.portal.data

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

internal object ContactActions {
    /** Only telephone numbers, never dialler service codes, URI schemes or pauses. */
    fun dialable(value: String): String? {
        if (!Regex("\\+?[0-9 ()/.-]{3,60}").matches(value)) return null
        return value.filter { it in '0'..'9' || it == '+' }.takeIf { it.count(Char::isDigit) >= 3 }
    }

    /** Compose only, in the configured Zimbra origin; never opens a local mail app or sends. */
    fun zimbraComposeUrl(baseUrl: String, email: String): String? {
        if (email.length > 254 || !Regex("[A-Za-z0-9._+%-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}").matches(email)) return null
        val base = runCatching { URI(baseUrl) }.getOrNull() ?: return null
        if (!base.scheme.equals("https", ignoreCase = true) || base.host.isNullOrBlank() ||
            base.rawUserInfo != null || base.rawQuery != null || base.rawFragment != null
        ) return null
        val compose = URI("https", null, base.host, base.port, "/modern/email/new", null, null).toASCIIString()
        // Modern UI's getParsedSearch first decodeURIComponent(search), then query-string.parse.
        // Encode twice so a '+' in the recipient is not changed into a space on the second pass.
        fun encode(value: String) = URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")
        return "$compose?to=${encode(encode(email))}"
    }

    /** Recognise only our own compose request, never a mailto or a different origin. */
    fun zimbraComposeRecipient(targetUrl: String, baseUrl: String): String? = runCatching {
        val target = URI(targetUrl)
        val base = URI(baseUrl)
        fun port(uri: URI) = if (uri.port == -1) 443 else uri.port
        if (!target.scheme.equals("https", true) || !base.scheme.equals("https", true) ||
            !target.host.equals(base.host, true) || port(target) != port(base) ||
            target.rawUserInfo != null || target.rawFragment != null || target.path != "/modern/email/new"
        ) return null
        val query = target.rawQuery.orEmpty()
        if (!query.startsWith("to=") || query.contains('&')) return null
        val email = URLDecoder.decode(URLDecoder.decode(query.substring(3), StandardCharsets.UTF_8), StandardCharsets.UTF_8)
        email.takeIf { zimbraComposeUrl(baseUrl, it) != null }
    }.getOrNull()
}
