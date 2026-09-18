package de.missionleben.portal.device

import android.os.Build
import de.missionleben.portal.BuildConfig
import de.missionleben.portal.model.DeviceMode
import de.missionleben.portal.model.EnrollmentState
import de.missionleben.portal.model.LinkTarget
import de.missionleben.portal.push.NotificationDetail
import de.missionleben.portal.push.NotificationPrivacy
import de.missionleben.portal.push.PushAction
import de.missionleben.portal.security.DeviceIdentity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64

data class EnrollmentResult(
    val deviceId: String,
    val trusted: Boolean,
)

class NotificationFetchException(
    val retryable: Boolean,
    message: String,
) : Exception(message)

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

    suspend fun registerPush(
        accessToken: String,
        deviceId: String,
        installationId: String,
        mode: DeviceMode,
        privacy: NotificationPrivacy,
    ) = withContext(Dispatchers.IO) {
        require(configured) { "Der Mission-Leben Device Service ist noch nicht konfiguriert." }
        val body = JSONObject()
            .put("provider", "fcm")
            .put("installation_id", installationId)
            .put("mode", mode.name.lowercase())
            .put("notification_privacy", privacy.wireName)
            .put("app_version", BuildConfig.VERSION_NAME)
        request("/v1/push/registrations/${encodePathSegment(deviceId)}", "PUT", body.toString(), accessToken)
    }

    suspend fun unregisterPush(accessToken: String, deviceId: String) = withContext(Dispatchers.IO) {
        if (!configured) return@withContext
        request("/v1/push/registrations/${encodePathSegment(deviceId)}", "DELETE", null, accessToken)
    }

    suspend fun deviceStatus(
        deviceId: String,
        identity: DeviceIdentity,
    ): EnrollmentState = withContext(Dispatchers.IO) {
        require(configured) { "Der Mission-Leben Device Service ist noch nicht konfiguriert." }
        val path = "/v1/devices/${encodePathSegment(deviceId)}/status"
        val response = performRequest(
            path = path,
            method = "GET",
            body = null,
            accessToken = null,
            headers = signedHeaders(path, deviceId, identity),
        )
        if (response.status !in 200..299) {
            error("Gerätestatus ist nicht verfügbar (HTTP ${response.status}).")
        }
        when (JSONObject(response.body).getString("status")) {
            "pending" -> EnrollmentState.PENDING
            "trusted" -> EnrollmentState.TRUSTED
            "blocked" -> EnrollmentState.BLOCKED
            else -> error("Unbekannter Gerätestatus.")
        }
    }

    suspend fun notificationDetail(
        eventId: String,
        expectedAction: PushAction,
        deviceId: String,
        identity: DeviceIdentity,
    ): NotificationDetail = withContext(Dispatchers.IO) {
        require(configured) { "Der Mission-Leben Device Service ist noch nicht konfiguriert." }
        require(eventId.matches(Regex("[A-Za-z0-9_-]{16,128}"))) { "Ungültige Ereignis-ID." }
        val path = "/v1/notifications/$eventId"
        val response = performRequest(
            path = path,
            method = "GET",
            body = null,
            accessToken = null,
            headers = signedHeaders(path, deviceId, identity),
        )
        if (response.status !in 200..299) {
            throw NotificationFetchException(
                retryable = response.status == 408 || response.status == 429 || response.status >= 500,
                message = "Benachrichtigungsdetails sind nicht verfügbar (HTTP ${response.status}).",
            )
        }
        val detail = runCatching { NotificationDetail.fromJson(JSONObject(response.body)) }
            .getOrElse { throw NotificationFetchException(false, "Ungültige Benachrichtigungsdetails.") }
        if (detail.eventId != eventId || detail.action != expectedAction) {
            throw NotificationFetchException(false, "Benachrichtigungsdetails passen nicht zum Ereignis.")
        }
        detail
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

    private fun encodePathSegment(value: String): String = java.net.URLEncoder.encode(value, Charsets.UTF_8.name())
        .replace("+", "%20")

    private fun signedHeaders(path: String, deviceId: String, identity: DeviceIdentity): Map<String, String> {
        val timestamp = Instant.now().epochSecond.toString()
        val nonceBytes = ByteArray(18).also(SecureRandom()::nextBytes)
        val nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(nonceBytes)
        val keyId = identity.keyId()
        val canonical = DeviceRequestSignature.canonical(
            method = "GET",
            path = path,
            deviceId = deviceId,
            keyId = keyId,
            timestamp = timestamp,
            nonce = nonce,
        )
        return mapOf(
            "X-ML-Device-ID" to deviceId,
            "X-ML-Key-ID" to keyId,
            "X-ML-Timestamp" to timestamp,
            "X-ML-Nonce" to nonce,
            "X-ML-Signature" to identity.sign(canonical),
        )
    }

    private fun request(path: String, method: String, body: String?, accessToken: String?): String {
        val response = performRequest(path, method, body, accessToken)
        if (response.status !in 200..299) {
            error("Device Service antwortet mit HTTP ${response.status}: ${response.body.take(180)}")
        }
        return response.body
    }

    private fun performRequest(
        path: String,
        method: String,
        body: String?,
        accessToken: String?,
        headers: Map<String, String> = emptyMap(),
    ): HttpResponse {
        val connection = URL(BuildConfig.DEVICE_SERVICE_BASE_URL.trimEnd('/') + path).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "MissionLebenPortal/${BuildConfig.VERSION_NAME}")
            if (!accessToken.isNullOrBlank()) connection.setRequestProperty("Authorization", "Bearer $accessToken")
            headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.bufferedWriter().use { it.write(body) }
            }
            val responseCode = connection.responseCode
            val response = (if (responseCode in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            return HttpResponse(responseCode, response)
        } finally {
            connection.disconnect()
        }
    }

    private data class HttpResponse(val status: Int, val body: String)
}
