package de.missionleben.portal

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import de.missionleben.portal.data.AppPreferences
import de.missionleben.portal.push.PushManager
import de.missionleben.portal.security.SharedSessionLifecyclePolicy

class MissionLebenApplication : Application() {
    private lateinit var preferences: AppPreferences
    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (
                intent?.action == Intent.ACTION_SCREEN_OFF &&
                SharedSessionLifecyclePolicy.shouldTrack(preferences.deviceMode)
            ) {
                preferences.markSharedSessionScreenTurnedOff()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        preferences = AppPreferences(this)
        registerReceiver(screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
        PushManager.initialize(this)
    }

    companion object {
        @Volatile
        var portalVisible: Boolean = false
            private set

        fun setPortalVisible(visible: Boolean) {
            portalVisible = visible
        }
    }
}
