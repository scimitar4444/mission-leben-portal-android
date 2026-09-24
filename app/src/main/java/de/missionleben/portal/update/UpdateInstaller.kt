package de.missionleben.portal.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

object UpdateInstaller {
    internal const val ACTION_INSTALL_COMMIT = "de.missionleben.portal.update.INSTALL_COMMIT"
    private const val STATUS_PREFERENCES = "update_install_status"
    private const val KEY_LAST_FAILURE = "last_failure"

    fun canRequestInstalls(context: Context): Boolean =
        context.packageManager.canRequestPackageInstalls()

    fun permissionIntent(context: Context): Intent = Intent(
        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
        Uri.parse("package:${context.packageName}"),
    )

    /** Android may still request confirmation; the receiver handles that fallback. */
    fun install(context: Context, apk: File) {
        require(apk.isFile && apk.length() > 0L) { "Verified update file is unavailable" }
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
            setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val sessionId = installer.createSession(params)
        var committed = false
        try {
            installer.openSession(sessionId).use { session ->
                session.openWrite("base.apk", 0, apk.length()).use { output ->
                    apk.inputStream().use { input -> input.copyTo(output) }
                    session.fsync(output)
                }
                val callback = Intent(context, UpdateInstallReceiver::class.java).apply {
                    action = ACTION_INSTALL_COMMIT
                    putExtra(PackageInstaller.EXTRA_SESSION_ID, sessionId)
                }
                val sender = PendingIntent.getBroadcast(
                    context,
                    sessionId,
                    callback,
                    PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_MUTABLE,
                ).intentSender
                session.commit(sender)
                committed = true
            }
        } finally {
            if (!committed) installer.abandonSession(sessionId)
        }
    }

    internal fun recordFailure(context: Context) {
        context.getSharedPreferences(STATUS_PREFERENCES, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_LAST_FAILURE, true).apply()
    }

    fun consumeFailure(context: Context): Boolean {
        val preferences = context.getSharedPreferences(STATUS_PREFERENCES, Context.MODE_PRIVATE)
        val failed = preferences.getBoolean(KEY_LAST_FAILURE, false)
        if (failed) preferences.edit().remove(KEY_LAST_FAILURE).apply()
        return failed
    }

    @Suppress("DEPRECATION")
    fun installIntent(context: Context, apk: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
        return Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
            setDataAndType(uri, APK_MIME_TYPE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private const val APK_MIME_TYPE = "application/vnd.android.package-archive"
}
