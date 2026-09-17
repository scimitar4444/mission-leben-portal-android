package de.missionleben.portal

import android.app.Application
import de.missionleben.portal.push.PushManager

class MissionLebenApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        PushManager.initialize(this)
    }
}
