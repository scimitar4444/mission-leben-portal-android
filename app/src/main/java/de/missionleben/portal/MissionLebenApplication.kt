package de.missionleben.portal

import android.app.Application
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import de.missionleben.portal.data.AppPreferences
import de.missionleben.portal.model.DeviceMode
import de.missionleben.portal.push.PushManager
import de.missionleben.portal.security.PersonalSessionLockTracker
import de.missionleben.portal.security.SharedSessionLifecyclePolicy

class MissionLebenApplication : Application() {
    private lateinit var preferences: AppPreferences
    val personalSessionLockTracker = PersonalSessionLockTracker(SystemClock::elapsedRealtime)
    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                when {
                    SharedSessionLifecyclePolicy.shouldTrack(preferences.deviceMode) ->
                        preferences.markSharedSessionScreenTurnedOff()
                    preferences.deviceMode == DeviceMode.PERSONAL ->
                        personalSessionLockTracker.screenTurnedOff()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        preferences = AppPreferences(this)
        registerReceiver(screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) = personalSessionLockTracker.activityStarted()
            override fun onActivityStopped(activity: Activity) {
                personalSessionLockTracker.activityStopped()
                if (preferences.deviceMode == DeviceMode.PERSONAL &&
                    !getSystemService(PowerManager::class.java).isInteractive
                ) personalSessionLockTracker.screenTurnedOff()
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
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
