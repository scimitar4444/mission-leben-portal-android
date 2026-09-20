package de.missionleben.portal.push

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import de.missionleben.portal.BuildConfig
import de.missionleben.portal.MainActivity
import de.missionleben.portal.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.min
import kotlin.random.Random

class NtfySubscriberService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var vault: NtfyCredentialVault
    private var subscriber: Job? = null

    @Volatile
    private var connection: HttpURLConnection? = null

    override fun onCreate() {
        super.onCreate()
        vault = NtfyCredentialVault(this)
        startAsForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (subscriber?.isActive != true) subscriber = scope.launch { subscribeLoop() }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        connection?.disconnect()
        connection = null
        scope.cancel()
        super.onDestroy()
    }

    private fun startAsForeground() {
        val notification = connectionNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                PushManager.CONNECTION_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING,
            )
        } else {
            startForeground(PushManager.CONNECTION_NOTIFICATION_ID, notification)
        }
    }

    private fun connectionNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            PushManager.CONNECTION_NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, "connection")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.push_connection_title))
            .setContentText(getString(R.string.push_connection_body))
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .build()
    }

    private suspend fun subscribeLoop() {
        var retryDelay = 1_000L
        while (scope.isActive) {
            val subscription = vault.load()
            if (subscription == null || !validSubscription(subscription)) {
                stopSelf()
                return
            }
            try {
                stream(subscription)
                retryDelay = 1_000L
            } catch (error: CancellationException) {
                throw error
            } catch (_: UnauthorizedSubscription) {
                Log.w(TAG, "ntfy subscription was revoked")
                vault.clear()
                sendBroadcast(
                    Intent(PushEventDispatcher.ACTION_REGISTRATION_CHANGED).setPackage(packageName),
                )
                stopSelf()
                return
            } catch (error: Exception) {
                Log.w(TAG, "ntfy stream interrupted; reconnecting", error)
            } finally {
                connection?.disconnect()
                connection = null
            }
            val jitter = Random.nextLong(0, min(1_000L, retryDelay / 3 + 1))
            delay(retryDelay + jitter)
            retryDelay = min(retryDelay * 2, 60_000L)
        }
    }

    private fun stream(subscription: NtfySubscription) {
        val since = subscription.lastMessageId.takeIf(NtfySubscriptionPolicy::validMessageId) ?: "10m"
        val topic = URLEncoder.encode(subscription.topic, Charsets.UTF_8.name()).replace("+", "%20")
        val sinceValue = URLEncoder.encode(since, Charsets.UTF_8.name())
        val activeConnection = URL("${subscription.baseUrl}/$topic/json?since=$sinceValue")
            .openConnection() as HttpURLConnection
        connection = activeConnection
        try {
            activeConnection.instanceFollowRedirects = false
            activeConnection.requestMethod = "GET"
            activeConnection.connectTimeout = 15_000
            activeConnection.readTimeout = 180_000
            activeConnection.useCaches = false
            activeConnection.setRequestProperty("Accept", "application/x-ndjson")
            activeConnection.setRequestProperty("Authorization", "Bearer ${subscription.token}")
            activeConnection.setRequestProperty("User-Agent", "MissionLebenPortal/${BuildConfig.VERSION_NAME}")
            val status = activeConnection.responseCode
            if (status == 401 || status == 403) throw UnauthorizedSubscription()
            if (status !in 200..299) throw IOException("ntfy returned HTTP $status")
            activeConnection.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    if (scope.isActive) handleLine(line)
                }
            }
        } finally {
            activeConnection.disconnect()
        }
    }

    private fun handleLine(line: String) {
        if (line.isBlank()) return
        val envelope = runCatching { JSONObject(line) }.getOrNull() ?: return
        if (envelope.optString("event") != "message") return
        val id = envelope.optString("id")
        val message = runCatching { JSONObject(envelope.optString("message")) }.getOrNull() ?: return
        val data = buildMap {
            message.keys().forEach { key -> put(key, message.optString(key)) }
        }
        val command = PushCommand.parse(data) ?: return
        PushEventDispatcher.dispatch(this, command)
        vault.rememberMessage(id)
    }

    private fun validSubscription(value: NtfySubscription): Boolean {
        return NtfySubscriptionPolicy.accepts(
            value.baseUrl,
            value.topic,
            value.token,
            BuildConfig.NTFY_PUBLIC_BASE_URL,
        )
    }

    private class UnauthorizedSubscription : IOException()

    private companion object {
        const val TAG = "PortalPush"
    }
}
