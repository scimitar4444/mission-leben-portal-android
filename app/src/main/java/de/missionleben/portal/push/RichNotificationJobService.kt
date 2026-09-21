package de.missionleben.portal.push

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.util.Log
import android.os.PersistableBundle
import de.missionleben.portal.data.AppPreferences
import de.missionleben.portal.device.DeviceServiceRepository
import de.missionleben.portal.device.NotificationFetchException
import de.missionleben.portal.model.EnrollmentState
import de.missionleben.portal.security.DeviceIdentity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

class RichNotificationJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<Int, Job>()

    override fun onStartJob(params: JobParameters): Boolean {
        val eventId = params.extras.getString(KEY_EVENT_ID, "").takeIf(PushCommand::validEventId)
            ?: return false
        val action = PushAction.fromWireName(params.extras.getString(KEY_EVENT_TYPE, ""))
            ?.takeUnless { it == PushAction.REFRESH_SECURITY_STATE }
            ?: return false
        val job = scope.launch {
            val shouldRetry = try {
                fetchAndDisplay(this@RichNotificationJobService, eventId, action)
                false
            } catch (error: NotificationFetchException) {
                error.retryable
            } catch (_: IOException) {
                true
            } catch (_: Exception) {
                false
            }
            jobs.remove(params.jobId)
            jobFinished(params, shouldRetry)
        }
        jobs[params.jobId] = job
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        jobs.remove(params.jobId)?.cancel()
        return true
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "PortalPush"
        private const val KEY_EVENT_ID = "event_id"
        private const val KEY_EVENT_TYPE = "event_type"
        private const val KEY_REVISION = "revision"

        internal suspend fun fetchAndDisplay(
            context: Context,
            eventId: String,
            action: PushAction,
        ): Boolean {
            val preferences = AppPreferences(context)
            val privacy = NotificationPrivacy.effective(
                preferences.deviceMode,
                PushRegistrationStore(context).personalPrivacy,
            )
            if (
                privacy == NotificationPrivacy.MINIMAL ||
                preferences.enrollmentState != EnrollmentState.TRUSTED
            ) {
                return false
            }
            val deviceId = preferences.deviceId ?: return false
            val repository = DeviceServiceRepository(context)
            if (!repository.communicationConfigured) return false
            val detail = repository.notificationDetail(eventId, action, deviceId, DeviceIdentity())
            NotificationPresenter.showRich(context, action, eventId, detail, privacy)
            return true
        }

        fun schedule(context: Context, command: PushCommand.Fetch): Boolean {
            val extras = PersistableBundle().apply {
                putString(KEY_EVENT_ID, command.eventId)
                putString(KEY_EVENT_TYPE, command.eventType.wireName)
                putString(KEY_REVISION, command.revision)
            }
            val info = JobInfo.Builder(
                NotificationPresenter.notificationId(command.eventId),
                ComponentName(context, RichNotificationJobService::class.java),
            )
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setExtras(extras)
                .build()
            return runCatching {
                context.getSystemService(JobScheduler::class.java).schedule(info) ==
                    JobScheduler.RESULT_SUCCESS
            }.onFailure {
                Log.w(TAG, "notification detail retry could not be scheduled", it)
            }.getOrDefault(false)
        }
    }
}
