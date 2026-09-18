package de.missionleben.portal.model

import androidx.annotation.StringRes
import de.missionleben.portal.R
import de.missionleben.portal.push.NotificationPrivacy

enum class DeviceMode(@StringRes val labelRes: Int) {
    PERSONAL(R.string.mode_personal),
    SHARED(R.string.mode_shared),
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
    val pushConfigured: Boolean = false,
    val notificationPrivacy: NotificationPrivacy = NotificationPrivacy.MINIMAL,
    val linkTargets: List<LinkTarget> = emptyList(),
    val enrollmentTokenPrefill: String = "",
    val requestedUrl: String? = null,
    val clearWebDataRequested: Boolean = false,
)
