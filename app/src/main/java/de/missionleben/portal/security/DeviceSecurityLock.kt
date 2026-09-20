package de.missionleben.portal.security

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import de.missionleben.portal.data.AppPreferences
import de.missionleben.portal.model.EnrollmentState
import de.missionleben.portal.push.PushManager
import de.missionleben.portal.web.PortalBrowserActivity
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

object DeviceSecurityLock {
    fun clearPersistentSession(context: Context) {
        val applicationContext = context.applicationContext
        AppPreferences(applicationContext).apply {
            enrollmentState = EnrollmentState.BLOCKED
            clearReauthentication()
        }
        SecureSessionVault(applicationContext).clear()
        NotificationManagerCompat.from(applicationContext).cancelAll()
        PushManager.stop(applicationContext)
    }

    suspend fun clearWebData(context: Context) {
        withTimeoutOrNull(10_000) {
            withContext(Dispatchers.Main.immediate) {
                suspendCancellableCoroutine { continuation ->
                    PortalBrowserActivity.clearLocalWebData(context.applicationContext) {
                        if (continuation.isActive) continuation.resume(Unit)
                    }
                }
            }
        }
    }
}
