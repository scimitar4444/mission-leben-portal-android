package de.missionleben.portal.device

import android.content.Context
import android.os.Build
import de.missionleben.portal.BuildConfig
import de.missionleben.portal.R
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

class DeviceServiceRepository(context: Context? = null) {
    private val context = context?.applicationContext
    private val credentialVault = context?.let(::DeviceCredentialVault)

    val endpointDevicesConfigured: Boolean
        get() = BuildConfig.AUTHENTIK_BASE_URL.startsWith("https://")

    val communicationConfigured: Boolean
        get() = BuildConfig.DEVICE_SERVICE_BASE_URL.startsWith("https://")

    suspend fun enroll(
        token: String,
        mode: DeviceMode,
        identity: DeviceIdentity,
    ): EnrollmentResult = withContext(Dispatchers.IO) {
        require(endpointDevicesConfigured) { text(R.string.device_service_not_configured) }
        val enrollmentToken = token.trim()
        require(enrollmentToken.length in 20..512 && enrollmentToken.none(Char::isWhitespace)) {
            text(R.string.message_enter_enrollment)
        }
        val identifier = "ml-android-${identity.keyId()}"
        val body = JSONObject()
            .put("device_serial", identifier)
            .put(
                "device_name",
                "${Build.MANUFACTURER} ${Build.MODEL} (${identity.keyId().take(8)})".trim(),
            )
        val enrollment = performAuthentikRequest(
            path = AGENT_ENROLL_PATH,
            method = "POST",
            body = body.toString(),
            authorization = "Bearer $enrollmentToken",
        )
        if (enrollment.status !in 200..299) {
            error(text(R.string.device_service_http_error, enrollment.status))
        }
        val agentToken = JSONObject(enrollment.body).getString("token")
        val config = agentRequest(AGENT_CONFIG_PATH, "GET", null, agentToken)
        if (config.status !in 200..299) {
            error(text(R.string.device_status_unavailable, config.status))
        }
        val deviceId = JSONObject(config.body).getString("device_id")
        credentialVault?.save(AuthentikDeviceCredential(deviceId, identifier, agentToken))
        runCatching { checkIn(agentToken, identifier, mode, identity) }
        EnrollmentResult(
            deviceId = deviceId,
            trusted = true,
        )
    }

    suspend fun linkTargets(accessToken: String): List<LinkTarget> = withContext(Dispatchers.IO) {
        if (!communicationConfigured) return@withContext emptyList()
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
        require(communicationConfigured) { text(R.string.device_service_not_configured) }
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
        require(communicationConfigured) { text(R.string.device_service_not_configured) }
        val credential = credentialVault?.load()
            ?: error(text(R.string.device_status_unknown))
        require(credential.deviceId == deviceId) { text(R.string.device_status_unknown) }
        val body = JSONObject()
            .put("provider", "fcm")
            .put("installation_id", installationId)
            .put("mode", mode.name.lowercase())
            .put("notification_privacy", privacy.wireName)
            .put("app_version", BuildConfig.VERSION_NAME)
            .put("authentik_device_token", credential.token)
            .put("key_id", DeviceIdentity().keyId())
            .put("public_key_jwk", DeviceIdentity().publicJwk())
        request("/v1/push/registrations/${encodePathSegment(deviceId)}", "PUT", body.toString(), accessToken)
    }

    suspend fun unregisterPush(accessToken: String, deviceId: String) = withContext(Dispatchers.IO) {
        if (!communicationConfigured) return@withContext
        request("/v1/push/registrations/${encodePathSegment(deviceId)}", "DELETE", null, accessToken)
    }

    suspend fun deviceStatus(
        deviceId: String,
        identity: DeviceIdentity,
    ): EnrollmentState = withContext(Dispatchers.IO) {
        require(endpointDevicesConfigured) { text(R.string.device_service_not_configured) }
        val credential = credentialVault?.load() ?: return@withContext EnrollmentState.NOT_ENROLLED
        if (credential.deviceId != deviceId || credential.identifier != "ml-android-${identity.keyId()}") {
            return@withContext EnrollmentState.BLOCKED
        }
        val response = agentRequest(AGENT_CONFIG_PATH, "GET", null, credential.token)
        when (response.status) {
            in 200..299 -> {
                runCatching { checkIn(credential.token, credential.identifier, null, identity) }
                EnrollmentState.TRUSTED
            }
            401, 403, 404 -> EnrollmentState.BLOCKED
            else -> error(text(R.string.device_status_unavailable, response.status))
        }
    }

    fun clearDeviceCredential() = credentialVault?.clear()

    fun hasDeviceCredential(deviceId: String?): Boolean =
        deviceId != null && credentialVault?.load()?.deviceId == deviceId

    fun signEndpointChallenge(challenge: String): String? =
        credentialVault?.load()?.let { EndpointChallengeSigner.sign(challenge, it) }

    suspend fun notificationDetail(
        eventId: String,
        expectedAction: PushAction,
        deviceId: String,
        identity: DeviceIdentity,
    ): NotificationDetail = withContext(Dispatchers.IO) {
        require(communicationConfigured) { text(R.string.device_service_not_configured) }
        require(eventId.matches(Regex("[A-Za-z0-9_-]{16,128}"))) { text(R.string.notification_event_invalid) }
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
                message = text(R.string.notification_details_unavailable, response.status),
            )
        }
        val detail = runCatching { NotificationDetail.fromJson(JSONObject(response.body)) }
            .getOrElse { throw NotificationFetchException(false, text(R.string.notification_details_invalid)) }
        if (detail.eventId != eventId || detail.action != expectedAction) {
            throw NotificationFetchException(false, text(R.string.notification_details_mismatch))
        }
        detail
    }

    internal fun extractTalkToken(value: String): String {
        val input = value.trim()
        val candidate = if ("://" in input) {
            val uri = runCatching { URI(input) }.getOrElse { throw IllegalArgumentException(text(R.string.talk_link_invalid)) }
            require(uri.scheme.equals("https", ignoreCase = true)) { text(R.string.talk_link_https_required) }
            val segments = uri.path.orEmpty().trim('/').split('/').filter(String::isNotBlank)
            val callIndex = segments.indexOfLast { it == "call" }
            require(callIndex >= 0 && callIndex + 1 < segments.size) { text(R.string.talk_link_not_nextcloud) }
            segments[callIndex + 1]
        } else {
            input
        }
        require(candidate.matches(Regex("[A-Za-z0-9_-]{6,128}"))) {
            text(R.string.talk_link_enter_valid)
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
            error(text(R.string.device_service_http_error, response.status))
        }
        return response.body
    }

    private fun agentRequest(path: String, method: String, body: String?, token: String): HttpResponse =
        performAuthentikRequest(path, method, body, "Bearer+Agent $token")

    private fun checkIn(
        token: String,
        identifier: String,
        mode: DeviceMode?,
        identity: DeviceIdentity,
    ) {
        val body = JSONObject()
            .put(
                "os",
                JSONObject()
                    .put("family", "android")
                    .put("name", "Android")
                    .put("version", Build.VERSION.RELEASE)
                    .put("arch", Build.SUPPORTED_ABIS.firstOrNull().orEmpty()),
            )
            .put(
                "hardware",
                JSONObject()
                    .put("manufacturer", Build.MANUFACTURER)
                    .put("model", Build.MODEL)
                    .put("serial", identifier),
            )
            .put(
                "software",
                JSONArray().put(
                    JSONObject()
                        .put("name", "Mission Leben Zentral")
                        .put("version", BuildConfig.VERSION_NAME)
                        .put("source", "android-app"),
                ),
            )
            .put(
                "vendor",
                JSONObject().put(
                    "mission-leben.de/portal",
                    JSONObject()
                        .put("mode", mode?.name?.lowercase().orEmpty())
                        .put("key_id", identity.keyId())
                        .put("app_version", BuildConfig.VERSION_NAME),
                ),
            )
        val response = agentRequest(AGENT_CHECK_IN_PATH, "POST", body.toString(), token)
        if (response.status !in 200..299) error("Authentik check-in failed (${response.status})")
    }

    private fun performAuthentikRequest(
        path: String,
        method: String,
        body: String?,
        authorization: String,
    ): HttpResponse {
        val connection = URL(BuildConfig.AUTHENTIK_BASE_URL.trimEnd('/') + path).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Authorization", authorization)
            connection.setRequestProperty("User-Agent", "MissionLebenPortal/${BuildConfig.VERSION_NAME}")
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

    private fun text(resourceId: Int, vararg formatArgs: Any): String =
        context?.getString(resourceId, *formatArgs) ?: "Invalid input."

    private companion object {
        const val AGENT_ENROLL_PATH = "/api/v3/endpoints/agents/connectors/enroll/"
        const val AGENT_CONFIG_PATH = "/api/v3/endpoints/agents/connectors/agent_config/"
        const val AGENT_CHECK_IN_PATH = "/api/v3/endpoints/agents/connectors/check_in/"
    }
}
