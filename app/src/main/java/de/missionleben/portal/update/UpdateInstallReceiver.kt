package de.missionleben.portal.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log

/** Receives the PackageInstaller result even if the app process was stopped. */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != UpdateInstaller.ACTION_INSTALL_COMMIT) return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        when (status) {
            PackageInstaller.STATUS_SUCCESS -> Log.i(TAG, "Update installed")
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirmation = intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                if (confirmation == null) {
                    UpdateInstaller.recordFailure(context)
                    Log.e(TAG, "System requested confirmation without an intent")
                    return
                }
                runCatching {
                    confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(confirmation)
                }.onFailure {
                    UpdateInstaller.recordFailure(context)
                    Log.e(TAG, "Unable to show system update confirmation", it)
                }
            }
            else -> {
                UpdateInstaller.recordFailure(context)
                Log.e(TAG, "Android rejected update: status=$status")
            }
        }
    }

    private companion object {
        const val TAG = "MissionLebenUpdate"
    }
}
