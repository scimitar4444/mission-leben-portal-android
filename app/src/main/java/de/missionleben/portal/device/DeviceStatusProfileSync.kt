package de.missionleben.portal.device

import android.content.Context
import de.missionleben.portal.data.AppPreferences
import de.missionleben.portal.model.DeviceMode
import de.missionleben.portal.model.EnrollmentProfile
import org.json.JSONObject

/** Apply only an authenticated successful status response for this exact device. */
internal object DeviceStatusProfileSync {
    fun accept(context: Context?, body: String, expectedDeviceId: String, mode: DeviceMode): Boolean =
        runCatching {
            val payload = JSONObject(body)
            require(payload.getString("device_id") == expectedDeviceId) { "Unexpected device" }
            val marker = if (payload.has("enrollment_profile") && !payload.isNull("enrollment_profile")) {
                payload.getString("enrollment_profile")
            } else null
            val profile = EnrollmentProfile.fromDeviceStatus(marker, mode)
            // Never infer PERSONAL_EMPLOYEE merely from PERSONAL mode or a missing local value.
            if (profile != null && context != null) AppPreferences(context).enrollmentProfile = profile
            true
        }.getOrDefault(false)
}
