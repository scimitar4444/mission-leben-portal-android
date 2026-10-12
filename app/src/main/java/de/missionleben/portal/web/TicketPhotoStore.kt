@file:Suppress("DEPRECATION")

package de.missionleben.portal.web

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt

/** Only temporary, app-private ticket photos; never writes to MediaStore or Downloads. */
internal object TicketPhotoStore {
    const val MAX_EDGE = 1600
    private const val MAX_CAPTURE_BYTES = 32L * 1024 * 1024
    private val ownedName = Regex("(?:capture|ticket-photo)-[a-f0-9-]{36}\\.jpg")

    private fun directory(context: Context) = File(context.cacheDir, "ticket-photos")

    fun createCapture(context: Context): File {
        val directory = directory(context)
        check(directory.isDirectory || directory.mkdir())
        return File(directory, "capture-${UUID.randomUUID()}.jpg").apply { check(createNewFile()) }
    }

    fun uri(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.ticket-photos", file)

    fun discard(context: Context, file: File?) {
        if (file == null || !ownedName.matches(file.name)) return
        if (runCatching { file.canonicalFile.parentFile == directory(context).canonicalFile }.getOrDefault(false)) {
            runCatching { context.revokeUriPermission(uri(context, file),
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            runCatching { file.delete() }
        }
    }

    fun clearAll(context: Context) {
        directory(context).listFiles()?.forEach { discard(context, it) }
    }

    fun prepare(context: Context, capture: File): File {
        require(capture.canonicalFile.parentFile == directory(context).canonicalFile && ownedName.matches(capture.name))
        require(capture.length() in 1..MAX_CAPTURE_BYTES)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(capture.path, bounds)
        require(bounds.outWidth in 1..30000 && bounds.outHeight in 1..30000)
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, MAX_EDGE)
        }
        val source = requireNotNull(BitmapFactory.decodeFile(capture.path, options))
        var rotated: Bitmap? = null
        var scaled: Bitmap? = null
        val output = File(directory(context), "ticket-photo-${UUID.randomUUID()}.jpg")
        try {
            val orientation = runCatching { ExifInterface(capture.path)
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
                .getOrDefault(ExifInterface.ORIENTATION_NORMAL)
            rotated = Bitmap.createBitmap(source, 0, 0, source.width, source.height, orientationMatrix(orientation), true)
            val factor = minOf(1f, MAX_EDGE.toFloat() / max(rotated.width, rotated.height))
            scaled = if (factor < 1f) Bitmap.createScaledBitmap(rotated,
                max(1, (rotated.width * factor).roundToInt()), max(1, (rotated.height * factor).roundToInt()), true) else rotated
            // Re-encoding pixels drops EXIF (including location/camera metadata).
            output.outputStream().use { check(scaled.compress(Bitmap.CompressFormat.JPEG, 85, it)) }
            check(output.length() in 1..(4L * 1024 * 1024))
            return output
        } catch (error: Throwable) {
            discard(context, output)
            throw error
        } finally {
            listOfNotNull(source, rotated, scaled).distinct().forEach { it.recycle() }
        }
    }

    fun preview(file: File): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        require(bounds.outWidth in 1..MAX_EDGE && bounds.outHeight in 1..MAX_EDGE)
        return requireNotNull(BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, 800)
        }))
    }

    internal fun sampleSize(width: Int, height: Int, target: Int): Int {
        require(width > 0 && height > 0 && target > 0)
        var sample = 1
        while (max(width, height) / sample > target * 2) sample *= 2
        return sample
    }

    internal fun orientationMatrix(orientation: Int) = Matrix().apply {
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(-90f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(-90f)
        }
    }
}
