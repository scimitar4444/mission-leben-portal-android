package de.missionleben.portal.model

/** The server-confirmed enrollment purpose, distinct from the app's UI mode. */
enum class EnrollmentProfile {
    PERSONAL_EMPLOYEE,
    FACILITY_TABLET,
    SHARED_ACCOUNT_HANDSET;

    fun matches(mode: DeviceMode): Boolean = when (this) {
        PERSONAL_EMPLOYEE, SHARED_ACCOUNT_HANDSET -> mode == DeviceMode.PERSONAL
        FACILITY_TABLET -> mode == DeviceMode.SHARED
    }

    companion object {
        fun defaultFor(mode: DeviceMode): EnrollmentProfile = when (mode) {
            DeviceMode.PERSONAL -> PERSONAL_EMPLOYEE
            DeviceMode.SHARED -> FACILITY_TABLET
        }

        /** Only call this for a successful authenticated enrollment-portal response. */
        fun fromPortalResponse(marker: String?, mode: DeviceMode): EnrollmentProfile {
            val profile = when (marker?.takeIf(String::isNotBlank)) {
                null -> defaultFor(mode) // Existing portal responses have no profile marker.
                "personal-employee" -> PERSONAL_EMPLOYEE
                "facility-tablet" -> FACILITY_TABLET
                "shared-account-handset" -> SHARED_ACCOUNT_HANDSET
                else -> throw IllegalArgumentException("Unknown enrollment profile")
            }
            require(profile.matches(mode)) { "Enrollment profile does not match device mode" }
            return profile
        }
    }
}
