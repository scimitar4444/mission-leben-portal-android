package de.missionleben.portal.web

import android.app.DownloadManager
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import java.util.UUID

/** Tracks only app-owned, user-requested public exports; never stores cookies or URLs. */
object PersonalDownloads {
    internal const val PREFERENCES = "personal_public_downloads"
    private const val IDS = "pending_ids"

    fun record(context: Context, id: Long) {
        require(id >= 0)
        val prefs = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        prefs.edit().putStringSet(IDS, pending(context).map(Long::toString).toSet() + id.toString()).apply()
    }

    fun pending(context: Context): Set<Long> =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getStringSet(IDS, emptySet()).orEmpty()
            .mapNotNull { it.toLongOrNull()?.takeIf { value -> value >= 0 } }.toSet()

    fun forget(context: Context, id: Long) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
            .putStringSet(IDS, (pending(context) - id).map(Long::toString).toSet()).apply()
    }

    fun status(context: Context, id: Long): Int? {
        if (id !in pending(context)) return null
        val manager = context.getSystemService(DownloadManager::class.java)
        return runCatching {
            manager.query(DownloadManager.Query().setFilterById(id)).use { cursor ->
                if (cursor == null || !cursor.moveToFirst()) null
                else cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            }
        }.getOrNull()
    }

    fun viewIntent(context: Context, id: Long): Intent? {
        if (status(context, id) != DownloadManager.STATUS_SUCCESSFUL) return null
        val manager = context.getSystemService(DownloadManager::class.java)
        val uri = runCatching { manager.getUriForDownloadedFile(id) }.getOrNull() ?: return null
        val mime = runCatching { manager.getMimeTypeForDownloadedFile(id) }.getOrNull()
        val filename = runCatching {
            manager.query(DownloadManager.Query().setFilterById(id)).use { cursor ->
                if (cursor == null || !cursor.moveToFirst()) null
                else cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE))
            }
        }.getOrNull()
        return viewIntent(uri, mime, filename)
    }

    internal fun viewIntent(uri: Uri, mime: String?, filename: String? = null): Intent? {
        // DownloadManager's provider grants access to one completed file, not the portal session.
        if (uri.scheme != "content" || uri.authority != "downloads" ||
            uri.query != null || uri.fragment != null
        ) return null
        val type = mimeType(mime, filename)
        return Intent(Intent.ACTION_VIEW).setDataAndType(uri, type)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .apply { clipData = ClipData.newRawUri("Dokument", uri) }
    }

    fun cancelPending(context: Context) {
        val manager = context.getSystemService(DownloadManager::class.java)
        pending(context).forEach { id ->
            // Completed exports belong to the user and survive logout. In-flight jobs do not.
            if (status(context, id) in setOf(
                    DownloadManager.STATUS_PENDING, DownloadManager.STATUS_RUNNING,
                    DownloadManager.STATUS_PAUSED, DownloadManager.STATUS_FAILED,
                )
            ) {
                runCatching { manager.remove(id) }
            }
        }
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().clear().apply()
    }

    fun uniqueFilename(original: String): String {
        val safe = original.replace(Regex("[^\\p{L}\\p{N}._ -]"), "_")
            .trim('.', ' ').ifBlank { "Download" }
        val extension = safe.substringAfterLast('.', "").takeIf {
            it.isNotEmpty() && it.length <= 12 && it.all(Char::isLetterOrDigit)
        }
        val base = if (extension == null) safe else safe.substringBeforeLast('.')
        val suffix = UUID.randomUUID().toString().take(8)
        return base.take(95) + "-" + suffix + (extension?.let { ".$it" } ?: "")
    }

    internal fun mimeType(serverType: String?, filename: String?): String {
        val supplied = serverType?.substringBefore(';')?.trim()?.lowercase()
            ?.takeIf { it.matches(Regex("[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+")) }
        if (supplied != null && supplied != "application/octet-stream") return supplied
        val extension = filename?.substringAfterLast('.', "")?.lowercase().orEmpty()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            ?: supplied ?: "application/octet-stream"
    }
}
