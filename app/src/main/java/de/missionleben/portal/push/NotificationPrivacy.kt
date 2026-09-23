package de.missionleben.portal.push

import androidx.annotation.StringRes
import de.missionleben.portal.R
import de.missionleben.portal.model.DeviceMode

enum class NotificationPrivacy(
    val wireName: String,
    @StringRes val labelRes: Int,
    @StringRes val descriptionRes: Int,
) {
    MINIMAL(
        wireName = "minimal",
        labelRes = R.string.privacy_minimal_label,
        descriptionRes = R.string.privacy_minimal_description,
    ),
    STANDARD(
        wireName = "standard",
        labelRes = R.string.privacy_standard_label,
        descriptionRes = R.string.privacy_standard_description,
    ),
    DETAILED(
        wireName = "detailed",
        labelRes = R.string.privacy_detailed_label,
        descriptionRes = R.string.privacy_detailed_description,
    );

    /** Local ceiling, independent of what a delayed/server-side response contains. */
    fun visiblePreview(action: PushAction, preview: String): String = when {
        this == DETAILED -> preview.take(280)
        this == STANDARD && action == PushAction.OPEN_TALK -> preview.take(120)
        else -> ""
    }

    companion object {
        fun fromWireName(value: String?): NotificationPrivacy? = entries.firstOrNull { it.wireName == value }

        fun effective(mode: DeviceMode?, personalPrivacy: NotificationPrivacy): NotificationPrivacy =
            if (mode == DeviceMode.PERSONAL) personalPrivacy else MINIMAL
    }
}
