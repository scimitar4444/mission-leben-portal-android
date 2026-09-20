package de.missionleben.portal.push

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import de.missionleben.portal.data.AppPreferences
import de.missionleben.portal.device.DeviceServiceRepository
import de.missionleben.portal.model.EnrollmentState
import de.missionleben.portal.security.DeviceIdentity
import de.missionleben.portal.security.DeviceSecurityLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class DeviceSecurityRefreshJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var refreshJob: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        refreshJob = scope.launch {
            val status = refreshSecurityState()
            if (status != null) {
                sendBroadcast(
                    Intent(PushEventDispatcher.ACTION_SECURITY_STATE_CHANGED)
                        .setPackage(packageName)
                        .putExtra(PushEventDispatcher.EXTRA_ENROLLMENT_STATE, status.name),
                )
            }
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        refreshJob?.cancel()
        refreshJob = null
        return false
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun refreshSecurityState(): EnrollmentState? {
        val preferences = AppPreferences(this)
        val deviceId = preferences.deviceId ?: return null
        val mode = preferences.deviceMode ?: return null
        val repository = DeviceServiceRepository(this)
        if (!repository.endpointDevicesConfigured) return null
        val status = runCatching {
            repository.deviceStatus(deviceId, mode, DeviceIdentity())
        }.getOrNull() ?: return null

        preferences.enrollmentState = status
        if (status == EnrollmentState.BLOCKED) {
            DeviceSecurityLock.clearPersistentSession(this)
            DeviceSecurityLock.clearWebData(this)
        }
        return status
    }

    companion object {
        private const val JOB_ID = 290_104_001

        fun schedule(context: Context) {
            val info = JobInfo.Builder(
                JOB_ID,
                ComponentName(context, DeviceSecurityRefreshJobService::class.java),
            )
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setExpedited(true)
                .build()
            context.getSystemService(JobScheduler::class.java).schedule(info)
        }
    }
}
