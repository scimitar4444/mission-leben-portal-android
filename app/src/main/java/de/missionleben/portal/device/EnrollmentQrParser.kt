package de.missionleben.portal.device

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

object EnrollmentQrParser {
    fun tokenFrom(value: String): String? {
        val uri = runCatching { URI(value.trim()) }.getOrNull() ?: return null
        if (!uri.scheme.equals(SCHEME, ignoreCase = true) || !uri.host.equals(HOST, ignoreCase = true)) {
            return null
        }
        val values = uri.rawQuery.orEmpty()
            .split('&')
            .mapNotNull { parameter ->
                val parts = parameter.split('=', limit = 2)
                if (parts.size != 2 || parts[0] != "token") null else runCatching {
                    URLDecoder.decode(parts[1], StandardCharsets.UTF_8)
                }.getOrNull()
            }
        if (values.size != 1) return null
        return values.single().takeIf { token ->
            token.length in 20..512 && token.none(Char::isWhitespace)
        }
    }

    private const val SCHEME = "de.missionleben.portal"
    private const val HOST = "enroll"
}
