package de.missionleben.portal

import android.app.Application
import de.missionleben.portal.push.PushManager

class MissionLebenApplication : Application() {
    override fun onCreate() {
        super.onCreate()
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
