package de.missionleben.portal.device

import android.os.Build
import de.missionleben.portal.BuildConfig
import de.missionleben.portal.model.DeviceMode
import de.missionleben.portal.model.LinkTarget
import de.missionleben.portal.security.DeviceIdentity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

data class EnrollmentResult(
    val deviceId: String,
    val trusted: Boolean,
)

class DeviceServiceRepository {
    val configured: Boolean
        get() = BuildConfig.DEVICE_SERVICE_BASE_URL.startsWith("https://")

    suspend fun enroll(
        token: String,
        mode: DeviceMode,
        identity: DeviceIdentity,
    ): EnrollmentResult = withContext(Dispatchers.IO) {
        require(configured) { "Der Mission-Leben Device Service ist noch nicht konfiguriert." }
        val body = JSONObject()
            .put("enrollment_token", token.trim())
            .put("mode", mode.name.lowercase())
            .put("device_name", "${Build.MANUFACTURER} ${Build.MODEL}".trim())
            .put("platform", "android")
            .put("os_version", Build.VERSION.RELEASE)
            .put("app_version", BuildConfig.VERSION_NAME)
            .put("key_id", identity.keyId())
            .put("public_key_jwk", identity.publicJwk())

        val result = JSONObject(request("/v1/enrollments", "POST", body.toString(), null))
        EnrollmentResult(
            deviceId = result.getString("device_id"),
            trusted = result.optString("status") == "trusted",
        )
    }

    suspend fun linkTargets(accessToken: String): List<LinkTarget> = withContext(Dispatchers.IO) {
        if (!configured) return@withContext emptyList()
        val root = JSONObject(request("/v1/link-targets?capability=open_talk", "GET", null, accessToken))
        val values = root.optJSONArray("results") ?: JSONArray()
        buildList {
            for (index in 0 until values.length()) {
                val item = values.getJSONObject(index)
                add(
                    LinkTarget(
                        id = item.getString("id"),
                        name = item.getString("name"),
                        location = item.optString("location"),
                        online = item.optBoolean("online"),
                    ),
                )
            }
        }
    }

    suspend fun openTalk(accessToken: String, targetId: String, talkUrl: String) = withContext(Dispatchers.IO) {
        require(configured) { "Der Mission-Leben Device Service ist noch nicht konfiguriert." }
        val token = extractTalkToken(talkUrl)
        val body = JSONObject()
            .put("target_device_id", targetId)
            .put("action", "open_talk")
            .put("room_token", token)
            .put("expires_in", 30)
        request("/v1/handoffs", "POST", body.toString(), accessToken)
    }

    internal fun extractTalkToken(value: String): String {
        val input = value.trim()
        val candidate = if ("://" in input) {
            val uri = runCatching { URI(input) }.getOrElse { throw IllegalArgumentException("Ungültiger Talk-Link.") }
            require(uri.scheme.equals("https", ignoreCase = true)) { "Talk-Links müssen HTTPS verwenden." }
            val segments = uri.path.orEmpty().trim('/').split('/').filter(String::isNotBlank)
            val callIndex = segments.indexOfLast { it == "call" }
            require(callIndex >= 0 && callIndex + 1 < segments.size) { "Der Link ist kein Nextcloud-Talk-Link." }
            segments[callIndex + 1]
        } else {
            input
        }
        require(candidate.matches(Regex("[A-Za-z0-9_-]{6,128}"))) {
            "Bitte einen gültigen Nextcloud-Talk-Link eingeben."
        }
        return candidate
    }

    private fun request(path: String, method: String, body: String?, accessToken: String?): String {
        val connection = URL(BuildConfig.DEVICE_SERVICE_BASE_URL.trimEnd('/') + path).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "MissionLebenPortal/${BuildConfig.VERSION_NAME}")
            if (!accessToken.isNullOrBlank()) connection.setRequestProperty("Authorization", "Bearer $accessToken")
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.bufferedWriter().use { it.write(body) }
            }
            val response = (if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (connection.responseCode !in 200..299) {
                error("Device Service antwortet mit HTTP ${connection.responseCode}: ${response.take(180)}")
            }
            return response
        } finally {
            connection.disconnect()
        }
    }
}
