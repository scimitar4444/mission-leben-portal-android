package de.missionleben.portal.model

enum class DeviceMode {
    PERSONAL,
    SHARED;

    val label: String
        get() = when (this) {
            PERSONAL -> "Persönliches Gerät"
            SHARED -> "Gemeinsam genutztes Tablet"
        }
}

enum class EnrollmentState {
    NOT_ENROLLED,
    PENDING,
    TRUSTED,
    BLOCKED,
}

enum class VaultRequest {
    NONE,
    SEAL,
    UNLOCK,
}

data class PortalApplication(
    val name: String,
    val slug: String,
    val launchUrl: String,
    val description: String = "",
    val publisher: String = "",
    val iconUrl: String = "",
)

data class LinkTarget(
    val id: String,
    val name: String,
    val location: String,
    val online: Boolean,
)

data class UserIdentity(
    val subject: String,
    val displayName: String,
    val email: String,
)

data class UiState(
    val mode: DeviceMode? = null,
    val busy: Boolean = false,
    val message: String? = null,
    val signedIn: Boolean = false,
    val user: UserIdentity? = null,
    val quickUnlockEnabled: Boolean = false,
    val vaultRequest: VaultRequest = VaultRequest.NONE,
    val applications: List<PortalApplication> = emptyList(),
    val applicationsLoading: Boolean = false,
    val enrollmentState: EnrollmentState = EnrollmentState.NOT_ENROLLED,
    val deviceId: String? = null,
    val deviceKeyId: String = "",
    val deviceServiceConfigured: Boolean = false,
    val linkTargets: List<LinkTarget> = emptyList(),
    val enrollmentTokenPrefill: String = "",
    val requestedUrl: String? = null,
)
