package de.missionleben.portal.calendar

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import de.missionleben.portal.data.AppPreferences
import de.missionleben.portal.device.CalendarAccessException
import de.missionleben.portal.device.DeviceServiceRepository
import de.missionleben.portal.model.DeviceMode
import de.missionleben.portal.model.EnrollmentState
import de.missionleben.portal.security.DeviceIdentity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class CalendarSyncOutcome { UPDATED, DISABLED, PERMISSION_MISSING, TEMPORARILY_UNAVAILABLE, ACCESS_REVOKED }

class CalendarSyncCoordinator(private val context: Context) {
    private val settings = CalendarSyncSettings(context)
    private val calendar = LocalCalendarStore(context)

    suspend fun sync(
        force: Boolean = false,
        suppliedSnapshot: CalendarSnapshot? = null,
        onSnapshot: (CalendarSnapshot) -> Unit = {},
    ): CalendarSyncOutcome = withContext(Dispatchers.IO) {
        if (!settings.enabled || AppPreferences(context).deviceMode != DeviceMode.PERSONAL) return@withContext CalendarSyncOutcome.DISABLED
        if (!calendar.hasPermission()) return@withContext CalendarSyncOutcome.PERMISSION_MISSING
        if (!force && System.currentTimeMillis() - settings.lastSuccessMillis < 15 * 60_000L) {
            return@withContext CalendarSyncOutcome.UPDATED
        }
        val deviceId = AppPreferences(context).deviceId ?: return@withContext CalendarSyncOutcome.TEMPORARILY_UNAVAILABLE
        val ownerSubject = settings.ownerSubject
        try {
            val snapshot = suppliedSnapshot ?: DeviceServiceRepository(context).calendarSnapshot(deviceId, DeviceIdentity())
            // A response that finished after logout/offboarding must not recreate local data.
            val current = AppPreferences(context)
            if (!settings.enabled || settings.ownerSubject != ownerSubject || current.deviceId != deviceId ||
                current.deviceMode != DeviceMode.PERSONAL || current.enrollmentState != EnrollmentState.TRUSTED) {
                return@withContext CalendarSyncOutcome.DISABLED
            }
            onSnapshot(snapshot)
            calendar.apply(snapshot, settings.days)
            settings.lastSuccessMillis = System.currentTimeMillis()
            CalendarSyncOutcome.UPDATED
        } catch (error: CalendarAccessException) {
            if (error.status == 401 || error.status == 403) {
                clearAndDisable()
                CalendarSyncOutcome.ACCESS_REVOKED
            } else CalendarSyncOutcome.TEMPORARILY_UNAVAILABLE
        } catch (_: Exception) {
            CalendarSyncOutcome.TEMPORARILY_UNAVAILABLE
        }
    }

    fun clearAndDisable() {
        settings.enabled = false
        settings.lastSuccessMillis = 0L
        CalendarSyncScheduler.cancel(context)
        calendar.clear()
    }
}

object CalendarSyncScheduler {
    private const val JOB_ID = 0x4D4C4341

    fun schedule(context: Context) {
        val scheduler = context.getSystemService(JobScheduler::class.java)
        val job = JobInfo.Builder(JOB_ID, ComponentName(context, CalendarSyncJobService::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPeriodic(6 * 60 * 60 * 1000L)
            .setPersisted(true)
            .build()
        scheduler.schedule(job)
    }

    fun cancel(context: Context) {
        context.getSystemService(JobScheduler::class.java).cancel(JOB_ID)
    }
}
