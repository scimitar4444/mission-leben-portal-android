package de.missionleben.portal.device

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.UUID

data class EnrollmentQrPayload(
    val token: String,
    val tokenUuid: String? = null,
    val mode: de.missionleben.portal.model.DeviceMode? = null,
)

object EnrollmentQrParser {
    fun parse(value: String): EnrollmentQrPayload? {
        val uri = runCatching { URI(value.trim()) }.getOrNull() ?: return null
        val rawParameters = when {
            uri.scheme.equals(APP_SCHEME, ignoreCase = true) &&
                uri.host.equals(APP_HOST, ignoreCase = true) &&
                uri.rawUserInfo == null &&
                uri.port == -1 &&
                uri.rawPath.isNullOrEmpty() &&
                uri.rawFragment == null -> uri.rawQuery
            uri.scheme.equals("https", ignoreCase = true) &&
                uri.host.equals(WEB_HOST, ignoreCase = true) &&
                uri.rawUserInfo == null &&
                uri.port == -1 &&
                uri.rawPath == WEB_PATH &&
                uri.rawQuery == null -> uri.rawFragment
            else -> null
        } ?: return null
        val values = rawParameters
            .split('&')
            .mapNotNull { parameter ->
                val parts = parameter.split('=', limit = 2)
                if (parts.size != 2) null else runCatching {
                    parts[0] to URLDecoder.decode(parts[1], StandardCharsets.UTF_8)
                }.getOrNull()
            }
            .groupBy({ it.first }, { it.second })
        if (values.keys.any { it !in ALLOWED_PARAMETERS }) return null
        val token = values["token"]?.singleOrNull()?.takeIf { candidate ->
            candidate.length in 20..512 && candidate.none(Char::isWhitespace)
        } ?: return null
        val tokenUuid = values["token_id"]?.singleOrNull()?.let { candidate ->
            runCatching { UUID.fromString(candidate).toString() }.getOrNull()
        }
        if ("token_id" in values && tokenUuid == null) return null
        val mode = when (values["mode"]?.singleOrNull()) {
            null -> null
            "personal" -> de.missionleben.portal.model.DeviceMode.PERSONAL
            "shared" -> de.missionleben.portal.model.DeviceMode.SHARED
            else -> return null
        }
        if ((tokenUuid == null) != (mode == null)) return null
        return EnrollmentQrPayload(token, tokenUuid, mode)
    }

    fun tokenFrom(value: String): String? = parse(value)?.token

    private const val APP_SCHEME = "de.missionleben.portal"
    private const val APP_HOST = "enroll"
    private const val WEB_HOST = "geraete.mission-leben.de"
    private const val WEB_PATH = "/install"
    private val ALLOWED_PARAMETERS = setOf("token", "token_id", "mode")
}
