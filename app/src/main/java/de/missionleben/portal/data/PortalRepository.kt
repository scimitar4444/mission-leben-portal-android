package de.missionleben.portal.data

import de.missionleben.portal.BuildConfig
import de.missionleben.portal.model.PortalApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

class PortalRepository {
    suspend fun applications(accessToken: String): List<PortalApplication> = withContext(Dispatchers.IO) {
        val endpoint = BuildConfig.AUTHENTIK_BASE_URL.trimEnd('/') +
            "/api/v3/core/applications/?only_with_launch_url=true&page_size=100&ordering=name"
        val response = request(endpoint, accessToken)
        val root = JSONObject(response)
        val results = root.optJSONArray("results") ?: return@withContext emptyList()

        buildList {
            for (index in 0 until results.length()) {
                val value = results.getJSONObject(index)
                if (!PortalApplicationFilter.isMobileGroup(value.optString("group"))) continue
                val launchUrl = value.optString("meta_launch_url")
                    .ifBlank { value.optString("launch_url") }
                if (launchUrl.isBlank()) continue
                add(
                    PortalApplication(
                        name = value.optString("name").ifBlank { value.optString("slug") },
                        slug = value.optString("slug"),
                        launchUrl = resolve(launchUrl),
                        description = value.optString("meta_description"),
                        publisher = value.optString("meta_publisher"),
                        iconUrl = resolve(value.optString("meta_icon")),
                    ),
                )
            }
        }
    }

    private fun request(endpoint: String, accessToken: String): String {
        val connection = URL(endpoint).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Authorization", "Bearer $accessToken")
            connection.setRequestProperty("User-Agent", "MissionLebenPortal/${BuildConfig.VERSION_NAME}")
            val body = (if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (connection.responseCode !in 200..299) {
                error("Authentik API antwortet mit HTTP ${connection.responseCode}: ${body.take(180)}")
            }
            return body
        } finally {
            connection.disconnect()
        }
    }

    private fun resolve(value: String): String {
        if (value.isBlank()) return ""
        return try {
            URI(BuildConfig.AUTHENTIK_BASE_URL.trimEnd('/') + "/").resolve(value).toString()
        } catch (_: Exception) {
            value
        }
    }
}

internal object PortalApplicationFilter {
    const val MOBILE_GROUP = "Mobil erreichbar"

    fun isMobileGroup(group: String?): Boolean = group?.trim() == MOBILE_GROUP
}
