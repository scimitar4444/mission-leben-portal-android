package de.missionleben.portal.push

import android.app.Application
import android.app.NotificationManager
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PushManagerTest {
    @Test
    fun `creates every channel used by a push action`() {
        val context = RuntimeEnvironment.getApplication()
        PushManager.createChannels(context)
        val manager = context.getSystemService(NotificationManager::class.java)

        PushAction.entries.forEach { action ->
            assertNotNull("Missing Android channel for ${action.wireName}", manager.getNotificationChannel(action.channelId))
        }
        assertNotNull(manager.getNotificationChannel("connection"))
    }
}
