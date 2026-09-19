package de.missionleben.portal.update

import java.net.URI

object UpdatePolicy {
    const val CHECK_INTERVAL_MILLIS = 6L * 60L * 60L * 1_000L
    const val MAX_APK_SIZE_BYTES = 200L * 1024L * 1024L

    private const val PACKAGE_NAME = "de.missionleben.portal"
    private const val RELEASE_PATH_PREFIX =
        "/scimitar4444/mission-leben-portal-android/releases/download/"
    private val sha256Pattern = Regex("^[0-9a-fA-F]{64}$")
    private val versionNamePattern = Regex("^[0-9]+(?:\\.[0-9]+){1,3}(?:[-+][0-9A-Za-z.-]+)?$")
    private val releaseAssetPathPattern = Regex(
        "^${Regex.escape(RELEASE_PATH_PREFIX)}[0-9A-Za-z._-]+/mission-leben-zentral\\.apk$",
    )

    fun shouldCheck(lastCheckEpochMillis: Long, nowEpochMillis: Long): Boolean =
        lastCheckEpochMillis <= 0L ||
            nowEpochMillis < lastCheckEpochMillis ||
            nowEpochMillis - lastCheckEpochMillis >= CHECK_INTERVAL_MILLIS

    fun availableUpdate(manifest: UpdateManifest, currentVersionCode: Long): AppUpdate? {
        require(manifest.schema == 1) { "Unsupported update manifest schema" }
        require(manifest.packageName == PACKAGE_NAME) { "Unexpected update package" }
        require(manifest.versionCode > 0L) { "Invalid update version code" }
        require(versionNamePattern.matches(manifest.versionName)) { "Invalid update version name" }
        require(isAllowedApkUrl(manifest.apkUrl)) { "Untrusted update URL" }
        require(sha256Pattern.matches(manifest.sha256)) { "Invalid update digest" }
        require(manifest.sizeBytes in 1L..MAX_APK_SIZE_BYTES) { "Invalid update size" }
        if (manifest.versionCode <= currentVersionCode) return null
        return AppUpdate(
            versionCode = manifest.versionCode,
            versionName = manifest.versionName,
            apkUrl = manifest.apkUrl,
            sha256 = manifest.sha256.lowercase(),
            sizeBytes = manifest.sizeBytes,
        )
    }

    fun isAllowedManifestUrl(value: String): Boolean {
        val uri = runCatching { URI(value) }.getOrNull() ?: return false
        return uri.scheme == "https" &&
            uri.host.equals("github.com", ignoreCase = true) &&
            uri.rawUserInfo == null &&
            uri.port == -1 &&
            uri.rawQuery == null &&
            uri.rawFragment == null &&
            uri.path == "/scimitar4444/mission-leben-portal-android/releases/latest/download/update.json"
    }

    fun isAllowedApkUrl(value: String): Boolean {
        val uri = runCatching { URI(value) }.getOrNull() ?: return false
        return uri.scheme == "https" &&
            uri.host.equals("github.com", ignoreCase = true) &&
            uri.rawUserInfo == null &&
            uri.port == -1 &&
            uri.rawQuery == null &&
            uri.rawFragment == null &&
            releaseAssetPathPattern.matches(uri.path)
    }

    fun isAllowedRedirectTransport(uri: URI): Boolean {
        if (uri.scheme != "https" || uri.rawUserInfo != null || uri.port != -1) return false
        val host = uri.host?.lowercase() ?: return false
        return host == "github.com" ||
            host == "release-assets.githubusercontent.com" ||
            host == "objects.githubusercontent.com"
    }
}
