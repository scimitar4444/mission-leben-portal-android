package de.missionleben.portal.setup

import android.content.Context
import de.missionleben.portal.model.DeviceMode
import java.security.MessageDigest

enum class SetupStep { NOTIFICATIONS, BATTERY, MESSAGES, CALENDAR }

object SetupAssistantPolicy {
    fun available(
        signedIn: Boolean,
        mode: DeviceMode?,
        pushConfigured: Boolean,
        communicationAvailable: Boolean,
        zimbraVisible: Boolean,
        calendarAvailable: Boolean,
    ): List<SetupStep> {
        if (!signedIn) return emptyList()
        return buildList {
            if (pushConfigured) {
                add(SetupStep.NOTIFICATIONS)
                add(SetupStep.BATTERY)
                if (mode == DeviceMode.PERSONAL && communicationAvailable) add(SetupStep.MESSAGES)
            }
            if (mode == DeviceMode.PERSONAL && zimbraVisible && calendarAvailable) add(SetupStep.CALENDAR)
        }
    }

    fun pending(available: List<SetupStep>, seen: Set<SetupStep>): List<SetupStep> =
        available.filterNot(seen::contains)
}

/** Local, per-device setup progress. The preference key contains no user name or subject. */
class SetupAssistantStore(context: Context) {
    private val preferences = context.getSharedPreferences("mission_leben_setup_assistant", Context.MODE_PRIVATE)

    fun scope(mode: DeviceMode?, deviceId: String?, subject: String?): String? {
        if (mode == null || deviceId.isNullOrBlank()) return null
        if (mode == DeviceMode.PERSONAL && subject.isNullOrBlank()) return null
        val identity = "$mode:$deviceId:${if (mode == DeviceMode.PERSONAL) subject else "shared"}"
        val digest = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray())
        return digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    fun seen(scope: String, legacyPushHandled: Boolean, calendarAlreadyEnabled: Boolean): Set<SetupStep> {
        val key = "seen_$scope"
        if (!preferences.contains(key)) {
            val initial = buildSet {
                if (legacyPushHandled) {
                    add(SetupStep.NOTIFICATIONS)
                    add(SetupStep.BATTERY)
                    add(SetupStep.MESSAGES)
                }
                if (calendarAlreadyEnabled) add(SetupStep.CALENDAR)
            }
            preferences.edit().putStringSet(key, initial.mapTo(mutableSetOf()) { it.name }).apply()
            return initial
        }
        return preferences.getStringSet(key, emptySet()).orEmpty()
            .mapNotNull { runCatching { SetupStep.valueOf(it) }.getOrNull() }
            .toSet()
    }

    fun markSeen(scope: String, steps: Collection<SetupStep>) {
        val key = "seen_$scope"
        val updated = preferences.getStringSet(key, emptySet()).orEmpty().toMutableSet()
        updated += steps.map(SetupStep::name)
        preferences.edit().putStringSet(key, updated).apply()
    }
}
