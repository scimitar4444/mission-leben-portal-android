package de.missionleben.portal.ui

import androidx.annotation.DrawableRes
import androidx.compose.ui.graphics.Color
import de.missionleben.portal.R
import de.missionleben.portal.model.PortalApplication
import java.util.Locale

/** Presentation only: never grants an application, capability or notification integration. */
internal enum class AppTileAppearance(
    @DrawableRes val iconRes: Int?,
    val lightForeground: Color,
    val lightBackground: Color,
    val darkForeground: Color,
    val darkBackground: Color,
) {
    MAIL(R.drawable.ic_app_mail, Color(0xFF08756D), Color(0xFFE3F3EF),
        Color(0xFF85D6C7), Color(0xFF203D39)),
    CHAT(R.drawable.ic_app_chat, Color(0xFF245AA7), Color(0xFFE9F0FC),
        Color(0xFFA6C8FF), Color(0xFF263752)),
    DOCUMENTS(R.drawable.ic_app_documents, Color(0xFF93601B), Color(0xFFFFF0D9),
        Color(0xFFF0CB88), Color(0xFF463824)),
    CLOUD(R.drawable.ic_app_cloud, Color(0xFF245AA7), Color(0xFFE9F0FC),
        Color(0xFFA6C8FF), Color(0xFF263752)),
    SECURITY(R.drawable.ic_app_lock, Color(0xFF725299), Color(0xFFF0EAF8),
        Color(0xFFD0B4EE), Color(0xFF3C2D4D)),
    VIDEO(R.drawable.ic_app_video, Color(0xFFAE445B), Color(0xFFFCEAF0),
        Color(0xFFFFAFC2), Color(0xFF492C38)),
    SUPPORT(R.drawable.ic_app_support, Color(0xFF08756D), Color(0xFFE3F3EF),
        Color(0xFF85D6C7), Color(0xFF203D39)),
    PRINT(R.drawable.ic_app_print, Color(0xFF576477), Color(0xFFECF0F5),
        Color(0xFFBDCADD), Color(0xFF303947)),
    DEVICE_INSTALL(R.drawable.ic_app_device_install, Color(0xFF245AA7), Color(0xFFE9F0FC),
        Color(0xFFA6C8FF), Color(0xFF263752)),
    DEVICE_SETUP(R.drawable.ic_app_device_setup, Color(0xFF08756D), Color(0xFFE3F3EF),
        Color(0xFF85D6C7), Color(0xFF203D39)),
    MONOGRAM(null, Color(0xFF725299), Color(0xFFF0EAF8),
        Color(0xFFD0B4EE), Color(0xFF3C2D4D));

    companion object {
        fun from(application: PortalApplication): AppTileAppearance {
            // Local vector assets work offline and do not add image downloads, trackers or
            // access-token forwarding. Future apps remain labelled by their own monogram.
            val slug = application.slug.lowercase(Locale.ROOT)
            val name = application.name.trim().lowercase(Locale.ROOT)
            return when {
                slug == "mission-leben-device-guide" -> DEVICE_INSTALL
                slug in setOf("mission-leben-device-manage", "mission-leben-device-init") -> DEVICE_SETUP
                "talk" in slug || name == "talk" -> CHAT
                "zimbra" in slug || "exchange-owa" in slug ||
                    name in setOf("zimbra mail", "zimbra", "owa", "exchange owa akademie") -> MAIL
                "projectsend" in slug || name in setOf("ml dokumente", "dokumente") -> DOCUMENTS
                "warden" in slug || name in setOf("warden", "vaultwarden") -> SECURITY
                "nextcloud" in slug || name == "nextcloud" -> CLOUD
                "peertube" in slug || name == "video zentral" -> VIDEO
                "otobo" in slug -> SUPPORT
                "savapage" in slug || "sava-page" in slug -> PRINT
                else -> MONOGRAM
            }
        }

        fun monogram(name: String): String {
            val trimmed = name.trim()
            return if (trimmed.isEmpty()) "?" else
                String(Character.toChars(trimmed.codePointAt(0))).uppercase(Locale.ROOT)
        }
    }
}
