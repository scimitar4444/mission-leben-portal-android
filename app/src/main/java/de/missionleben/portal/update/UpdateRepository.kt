package de.missionleben.portal.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import de.missionleben.portal.BuildConfig
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

class UpdateRepository(
    context: Context,
    private val manifestUrl: String = BuildConfig.UPDATE_MANIFEST_URL,
) {
    private val applicationContext = context.applicationContext

    suspend fun checkForUpdate(): AppUpdate? = withContext(Dispatchers.IO) {
        require(UpdatePolicy.isAllowedManifestUrl(manifestUrl)) { "Untrusted update manifest URL" }
        val payload = fetchBytes(URI(manifestUrl), MAX_MANIFEST_BYTES)
        val json = JSONObject(payload.toString(Charsets.UTF_8))
        val manifest = UpdateManifest(
            schema = json.getInt("schema"),
            packageName = json.getString("packageName"),
            versionCode = json.getLong("versionCode"),
            versionName = json.getString("versionName"),
            apkUrl = json.getString("apkUrl"),
            sha256 = json.getString("sha256"),
            sizeBytes = json.getLong("sizeBytes"),
        )
        UpdatePolicy.availableUpdate(manifest, BuildConfig.VERSION_CODE.toLong())
    }

    suspend fun downloadAndVerify(update: AppUpdate): File = withContext(Dispatchers.IO) {
        require(UpdatePolicy.isAllowedApkUrl(update.apkUrl)) { "Untrusted update URL" }
        val updateDirectory = File(applicationContext.cacheDir, UPDATE_DIRECTORY).apply { mkdirs() }
        require(updateDirectory.isDirectory) { "Update cache is unavailable" }
        val temporaryFile = File.createTempFile("update-", ".part", updateDirectory)
        try {
            val connection = openConnection(URI(update.apkUrl))
            try {
                val advertisedLength = connection.contentLengthLong
                require(advertisedLength <= 0L || advertisedLength == update.sizeBytes) {
                    "Update size does not match manifest"
                }
                val digest = MessageDigest.getInstance("SHA-256")
                var total = 0L
                connection.inputStream.use { input ->
                    temporaryFile.outputStream().buffered().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            require(total <= update.sizeBytes && total <= UpdatePolicy.MAX_APK_SIZE_BYTES) {
                                "Update exceeds declared size"
                            }
                            digest.update(buffer, 0, count)
                            output.write(buffer, 0, count)
                        }
                    }
                }
                require(total == update.sizeBytes) { "Incomplete update download" }
                require(digest.digest().toHex() == update.sha256) { "Update digest mismatch" }
            } finally {
                connection.disconnect()
            }
            verifyPackage(temporaryFile, update)
            val destination = File(updateDirectory, "mission-leben-zentral-${update.versionCode}.apk")
            runCatching {
                Files.move(
                    temporaryFile.toPath(),
                    destination.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            }.getOrElse {
                Files.move(
                    temporaryFile.toPath(),
                    destination.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
            updateDirectory.listFiles()
                ?.filter { it.isFile && it != destination }
                ?.forEach(File::delete)
            destination
        } catch (error: Throwable) {
            temporaryFile.delete()
            throw error
        }
    }

    private fun fetchBytes(uri: URI, maximumBytes: Long): ByteArray {
        val connection = openConnection(uri)
        return try {
            val advertisedLength = connection.contentLengthLong
            require(advertisedLength <= 0L || advertisedLength <= maximumBytes) { "Response is too large" }
            val output = ByteArrayOutputStream()
            var total = 0L
            connection.inputStream.use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= maximumBytes) { "Response is too large" }
                    output.write(buffer, 0, count)
                }
            }
            output.toByteArray()
        } finally {
            connection.disconnect()
        }
    }

    private fun openConnection(initialUri: URI): HttpURLConnection {
        var current = initialUri
        repeat(MAX_REDIRECTS + 1) { redirectCount ->
            require(UpdatePolicy.isAllowedRedirectTransport(current)) { "Untrusted update transport" }
            val connection = current.toURL().openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
            connection.readTimeout = READ_TIMEOUT_MILLIS
            connection.setRequestProperty("Accept-Encoding", "identity")
            connection.setRequestProperty("User-Agent", "MissionLebenPortal/${BuildConfig.VERSION_NAME}")
            val status = connection.responseCode
            if (status in 300..399) {
                val location = connection.getHeaderField("Location")
                connection.disconnect()
                require(redirectCount < MAX_REDIRECTS && !location.isNullOrBlank()) { "Invalid update redirect" }
                current = current.resolve(location)
            } else {
                require(status == HttpURLConnection.HTTP_OK) { "Update server returned HTTP $status" }
                return connection
            }
        }
        error("Too many update redirects")
    }

    private fun verifyPackage(file: File, update: AppUpdate) {
        val flags = PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong())
        val archive = requireNotNull(
            applicationContext.packageManager.getPackageArchiveInfo(file.absolutePath, flags),
        ) { "Downloaded file is not an APK" }
        val installed = applicationContext.packageManager.getPackageInfo(applicationContext.packageName, flags)
        require(archive.packageName == applicationContext.packageName) { "Update package name mismatch" }
        require(archive.longVersionCode == update.versionCode) { "Update version mismatch" }
        require(currentSignerDigests(archive) == currentSignerDigests(installed)) {
            "Update signing certificate mismatch"
        }
    }

    private fun currentSignerDigests(packageInfo: PackageInfo): Set<String> {
        val signers = requireNotNull(packageInfo.signingInfo) { "APK has no signing information" }
            .apkContentsSigners
        require(signers.isNotEmpty()) { "APK has no signing certificate" }
        return signers.map { signer ->
            MessageDigest.getInstance("SHA-256").digest(signer.toByteArray()).toHex()
        }.toSet()
    }

    private fun ByteArray.toHex(): String = joinToString(separator = "") {
        (it.toInt() and 0xff).toString(16).padStart(2, '0')
    }

    private companion object {
        const val UPDATE_DIRECTORY = "updates"
        const val MAX_MANIFEST_BYTES = 32L * 1024L
        const val MAX_REDIRECTS = 5
        const val CONNECT_TIMEOUT_MILLIS = 10_000
        const val READ_TIMEOUT_MILLIS = 60_000
    }
}
