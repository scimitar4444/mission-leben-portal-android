package de.missionleben.portal.device

import android.content.Context
import android.os.Build
import de.missionleben.portal.BuildConfig
import de.missionleben.portal.R
import de.missionleben.portal.model.DeviceMode
import de.missionleben.portal.model.AnnouncementItem
import de.missionleben.portal.model.EnrollmentState
import de.missionleben.portal.model.LinkTarget
import de.missionleben.portal.model.LoginApprovalRequest
import de.missionleben.portal.model.PortalCapability
import de.missionleben.portal.push.NotificationDetail
import de.missionleben.portal.push.NotificationPrivacy
import de.missionleben.portal.push.NtfySubscription
import de.missionleben.portal.push.NtfySubscriptionPolicy
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

data class AnnouncementResult(
    val items: List<AnnouncementItem>,
    val stale: Boolean,
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
        enrollment: EnrollmentQrPayload,
        mode: DeviceMode,
        identity: DeviceIdentity,
    ): EnrollmentResult = withContext(Dispatchers.IO) {
        require(endpointDevicesConfigured) { text(R.string.device_service_not_configured) }
        val enrollmentToken = enrollment.token.trim()
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
        val enrollmentResponse = if (enrollment.tokenUuid != null) {
            require(BuildConfig.ENROLLMENT_SERVICE_BASE_URL.startsWith("https://")) {
                text(R.string.device_service_not_configured)
            }
            val portalBody = JSONObject()
                .put("mode", mode.name.lowercase())
                .put("device_serial", identifier)
                .put("device_name", body.getString("device_name"))
            performEnrollmentPortalRequest(
                path = "/api/v1/enrollments/${enrollment.tokenUuid}/redeem",
                method = "POST",
                body = portalBody.toString(),
                authorization = "Bearer $enrollmentToken",
            )
        } else {
            performAuthentikRequest(
                path = AGENT_ENROLL_PATH,
                method = "POST",
                body = body.toString(),
                authorization = "Bearer $enrollmentToken",
            )
        }
        if (enrollmentResponse.status !in 200..299) {
            error(text(R.string.device_service_http_error, enrollmentResponse.status))
        }
        val agentToken = JSONObject(enrollmentResponse.body).getString("token")
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

    suspend fun capabilities(accessToken: String): Set<PortalCapability> = withContext(Dispatchers.IO) {
        if (!communicationConfigured) return@withContext emptySet()
        val root = JSONObject(request("/v1/capabilities", "GET", null, accessToken))
        val values = root.optJSONArray("capabilities") ?: JSONArray()
        buildSet {
            for (index in 0 until values.length()) {
                PortalCapability.fromWireName(values.optString(index))?.let(::add)
            }
        }
    }

    suspend fun announcements(accessToken: String): AnnouncementResult = withContext(Dispatchers.IO) {
        if (!communicationConfigured) return@withContext AnnouncementResult(emptyList(), false)
        val root = JSONObject(request("/v1/announcements", "GET", null, accessToken))
        val values = root.optJSONArray("results") ?: JSONArray()
        val items = buildList {
            for (index in 0 until values.length()) {
                val item = values.optJSONObject(index) ?: continue
                val subject = item.optString("subject").trim()
                if (subject.isEmpty()) continue
                add(
                    AnnouncementItem(
                        id = item.optLong("id"),
                        subject = subject,
                        message = item.optString("message").trim(),
                        author = item.optString("author").trim(),
                        publishedAtEpochSeconds = item.optLong("time"),
                    ),
                )
            }
        }
        AnnouncementResult(items, root.optBoolean("stale"))
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
        mode: DeviceMode,
        privacy: NotificationPrivacy,
        calendarReminderMinutes: Int,
    ): NtfySubscription = withContext(Dispatchers.IO) {
        require(communicationConfigured) { text(R.string.device_service_not_configured) }
        val credential = credentialVault?.load()
            ?: error(text(R.string.device_status_unknown))
        require(credential.deviceId == deviceId) { text(R.string.device_status_unknown) }
        val body = JSONObject()
            .put("provider", "ntfy")
            .put("mode", mode.name.lowercase())
            .put("notification_privacy", privacy.wireName)
            .put("calendar_reminder_minutes", calendarReminderMinutes)
            .put("app_version", BuildConfig.VERSION_NAME)
            .put("authentik_device_token", credential.token)
            .put("key_id", DeviceIdentity().keyId())
            .put("public_key_jwk", DeviceIdentity().publicJwk())
        parseNtfySubscription(
            request(
                "/v1/push/registrations/${encodePathSegment(deviceId)}",
                "PUT",
                body.toString(),
                accessToken,
            ),
        )
    }

    suspend fun registerLoginApproval(
        accessToken: String,
        deviceId: String,
        mode: DeviceMode,
    ) = withContext(Dispatchers.IO) {
        require(communicationConfigured) { text(R.string.device_service_not_configured) }
        require(mode == DeviceMode.PERSONAL) { text(R.string.login_approval_personal_only) }
        val credential = credentialVault?.load() ?: error(text(R.string.device_status_unknown))
        require(credential.deviceId == deviceId) { text(R.string.device_status_unknown) }
        val identity = DeviceIdentity()
        val body = JSONObject()
            .put("mode", "personal")
            .put("app_version", BuildConfig.VERSION_NAME)
            .put("authentik_device_token", credential.token)
            .put("key_id", identity.keyId())
            .put("public_key_jwk", identity.publicJwk())
        request(
            "/v1/auth/registrations/${encodePathSegment(deviceId)}",
            "PUT",
            body.toString(),
            accessToken,
        )
    }

    suspend fun unregisterPush(accessToken: String, deviceId: String) = withContext(Dispatchers.IO) {
        if (!communicationConfigured) return@withContext
        request("/v1/push/registrations/${encodePathSegment(deviceId)}", "DELETE", null, accessToken)
    }

    suspend fun unregisterCommunication(accessToken: String, deviceId: String) = withContext(Dispatchers.IO) {
        if (!communicationConfigured) return@withContext
        request("/v1/auth/registrations/${encodePathSegment(deviceId)}", "DELETE", null, accessToken)
    }

    suspend fun deviceStatus(
        deviceId: String,
        mode: DeviceMode,
        identity: DeviceIdentity,
    ): EnrollmentState = withContext(Dispatchers.IO) {
        require(endpointDevicesConfigured) { text(R.string.device_service_not_configured) }
        val credential = credentialVault?.load() ?: return@withContext EnrollmentState.NOT_ENROLLED
        if (credential.deviceId != deviceId || credential.identifier != "ml-android-${identity.keyId()}") {
            return@withContext EnrollmentState.BLOCKED
        }
        val response = performEnrollmentPortalRequest(
            path = DEVICE_STATUS_PATH,
            method = "GET",
            body = null,
            authorization = "Bearer+Agent ${credential.token}",
        )
        when (response.status) {
            in 200..299 -> {
                val verifiedDeviceId = runCatching {
                    JSONObject(response.body).getString("device_id")
                }.getOrElse { return@withContext EnrollmentState.BLOCKED }
                if (verifiedDeviceId != credential.deviceId) {
                    return@withContext EnrollmentState.BLOCKED
                }
                runCatching { checkIn(credential.token, credential.identifier, mode, identity) }
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
            headers = signedHeaders("GET", path, null, deviceId, identity),
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

    suspend fun pendingLoginApproval(
        deviceId: String,
        identity: DeviceIdentity,
    ): LoginApprovalRequest? = withContext(Dispatchers.IO) {
        require(communicationConfigured) { text(R.string.device_service_not_configured) }
        val path = "/v1/auth/requests/pending"
        val response = performRequest(
            path = path,
            method = "GET",
            body = null,
            accessToken = null,
            headers = signedHeaders("GET", path, null, deviceId, identity),
        )
        if (response.status !in 200..299) {
            error(text(R.string.device_service_http_error, response.status))
        }
        val root = JSONObject(response.body)
        if (root.isNull("request")) return@withContext null
        val request = root.getJSONObject("request")
        LoginApprovalRequest(
            requestId = request.getString("request_id"),
            application = request.optString("application"),
            domain = request.optString("domain"),
            requestedAtEpochSeconds = request.getLong("requested_at"),
            expiresAtEpochSeconds = request.getLong("expires_at"),
        )
    }

    suspend fun decideLoginApproval(
        requestId: String,
        approved: Boolean,
        deviceId: String,
        identity: DeviceIdentity,
    ) = withContext(Dispatchers.IO) {
        require(communicationConfigured) { text(R.string.device_service_not_configured) }
        require(requestId.matches(Regex("[A-Za-z0-9_-]{24,128}"))) {
            text(R.string.login_approval_invalid)
        }
        val path = "/v1/auth/requests/${encodePathSegment(requestId)}/decision"
        val body = JSONObject()
            .put("decision", if (approved) "approve" else "deny")
            .toString()
        val response = performRequest(
            path = path,
            method = "POST",
            body = body,
            accessToken = null,
            headers = signedHeaders("POST", path, body, deviceId, identity),
        )
        if (response.status !in 200..299) {
            error(text(R.string.device_service_http_error, response.status))
        }
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

    private fun signedHeaders(
        method: String,
        path: String,
        body: String?,
        deviceId: String,
        identity: DeviceIdentity,
    ): Map<String, String> {
        val timestamp = Instant.now().epochSecond.toString()
        val nonceBytes = ByteArray(18).also(SecureRandom()::nextBytes)
        val nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(nonceBytes)
        val keyId = identity.keyId()
        val canonical = DeviceRequestSignature.canonical(
            method = method,
            path = path,
            deviceId = deviceId,
            keyId = keyId,
            timestamp = timestamp,
            nonce = nonce,
            body = body.orEmpty(),
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

    private fun performEnrollmentPortalRequest(
        path: String,
        method: String,
        body: String?,
        authorization: String,
    ): HttpResponse {
        val connection = URL(BuildConfig.ENROLLMENT_SERVICE_BASE_URL.trimEnd('/') + path)
            .openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.requestMethod = method
            connection.connectTimeout = 10_000
            connection.readTimeout = 20_000
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

    internal fun parseNtfySubscription(value: String): NtfySubscription {
        val root = JSONObject(value)
        require(root.optString("provider") == "ntfy") { text(R.string.push_registration_invalid) }
        val baseUrl = root.optString("base_url").trimEnd('/')
        val topic = root.optString("topic")
        val token = root.optString("token")
        require(
            NtfySubscriptionPolicy.accepts(
                baseUrl,
                topic,
                token,
                BuildConfig.NTFY_PUBLIC_BASE_URL,
            ),
        ) {
            text(R.string.push_registration_invalid)
        }
        return NtfySubscription(baseUrl, topic, token)
    }

    private companion object {
        const val AGENT_ENROLL_PATH = "/api/v3/endpoints/agents/connectors/enroll/"
        const val AGENT_CONFIG_PATH = "/api/v3/endpoints/agents/connectors/agent_config/"
        const val AGENT_CHECK_IN_PATH = "/api/v3/endpoints/agents/connectors/check_in/"
        const val DEVICE_STATUS_PATH = "/api/v1/devices/status"
    }
}
