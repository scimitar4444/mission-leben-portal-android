package de.missionleben.portal.update

import android.content.Context

/** The installer of record owns updates once F-Droid installs a newer version. */
object UpdateChannel {
    private val fdroidPackages = setOf("org.fdroid.fdroid", "org.fdroid.basic")

    fun managedByFdroid(context: Context): Boolean {
        val installer = runCatching {
            context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName
        }.getOrNull()
        return isFdroidInstaller(installer)
    }

    internal fun isFdroidInstaller(packageName: String?): Boolean = packageName in fdroidPackages
}
