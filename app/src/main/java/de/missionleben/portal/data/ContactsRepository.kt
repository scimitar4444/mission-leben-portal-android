package de.missionleben.portal.data

import android.content.Context
import de.missionleben.portal.BuildConfig
import de.missionleben.portal.device.DeviceCredentialVault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class EmployeeContact(
    val id: String,
    val name: String,
    val email: String,
    val phone: String,
    val mobile: String,
    val jobTitle: String,
    val department: String,
    val facilities: List<String>,
)

data class ContactPage(
    val contacts: List<EmployeeContact>,
    val total: Int,
    val nextOffset: Int?,
    val myFacilities: List<String>,
)

class ContactsAccessException : IllegalStateException()

class ContactsRepository(context: Context) {
    private val credentialVault = DeviceCredentialVault(context.applicationContext)

    suspend fun search(token: String, query: String, mine: Boolean, offset: Int): ContactPage =
        withContext(Dispatchers.IO) {
            val credential = credentialVault.load() ?: throw ContactsAccessException()
            val connection = URL(BuildConfig.DEVICE_SERVICE_BASE_URL.trimEnd('/') + "/v1/contacts/search")
                .openConnection() as HttpURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.useCaches = false
                connection.connectTimeout = 10_000
                connection.readTimeout = 15_000
                connection.requestMethod = "POST"
                connection.setRequestProperty("Authorization", "Bearer $token")
                connection.setRequestProperty("X-ML-Device-Token", credential.token)
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.setRequestProperty("Accept", "application/json")
                connection.doOutput = true
                connection.outputStream.bufferedWriter(Charsets.UTF_8).use {
                    it.write(JSONObject().put("q", query.take(100)).put("mine", mine).put("offset", offset).toString())
                }
                when (connection.responseCode) {
                    401 -> throw PortalAuthenticationException()
                    403 -> throw ContactsAccessException()
                    200 -> Unit
                    else -> error("Directory unavailable")
                }
                val root = JSONObject(connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
                val entries = root.getJSONArray("results")
                ContactPage(
                    contacts = List(entries.length()) { index ->
                        val entry = entries.getJSONObject(index)
                        val facilities = entry.getJSONArray("facilities")
                        EmployeeContact(
                            entry.getString("id"), entry.getString("name"), entry.optString("email"),
                            entry.optString("phone"), entry.optString("mobile"), entry.optString("job_title"), entry.optString("department"),
                            List(facilities.length()) { facilities.getString(it) },
                        )
                    },
                    total = root.getInt("total"),
                    nextOffset = if (root.isNull("next_offset")) null else root.getInt("next_offset"),
                    myFacilities = root.getJSONArray("my_facilities").let { values ->
                        List(values.length()) { values.getString(it) }
                    },
                )
            } finally {
                connection.disconnect()
            }
        }
}
