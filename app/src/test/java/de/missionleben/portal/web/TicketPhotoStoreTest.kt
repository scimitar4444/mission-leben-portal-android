@file:Suppress("DEPRECATION")
package de.missionleben.portal.web

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import androidx.core.content.FileProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TicketPhotoStoreTest {
    private val context get() = RuntimeEnvironment.getApplication()
    @Before fun clearPhotos() {
        // AndroidX caches canonical roots process-wide; Robolectric changes app directories per test.
        ReflectionHelpers.getStaticField<MutableMap<String, Any>>(FileProvider::class.java, "sCache").clear()
        TicketPhotoStore.clearAll(context)
    }

    private fun image(width: Int, height: Int): File = TicketPhotoStore.createCapture(context).also { file ->
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)) }
        bitmap.recycle()
    }

    @Test fun createsUniquePrivateFilesAndScopedContentUris() {
        val first = TicketPhotoStore.createCapture(context)
        val second = TicketPhotoStore.createCapture(context)
        assertNotEquals(first, second)
        assertEquals(File(context.cacheDir, "ticket-photos").canonicalFile, first.canonicalFile.parentFile)
        val uri = TicketPhotoStore.uri(context, first)
        assertEquals("content", uri.scheme)
        assertEquals("${context.packageName}.ticket-photos", uri.authority)
        assertTrue(uri.path!!.startsWith("/ticket_photos/"))
    }

    @Test fun cleanupNeverDeletesAnotherDirectoryOrAnUnownedFile() {
        val photo = TicketPhotoStore.createCapture(context)
        val elsewhere = File(context.cacheDir, photo.name).apply { writeText("keep") }
        val unowned = File(photo.parentFile, "other-cache.txt").apply { writeText("keep") }
        TicketPhotoStore.discard(context, elsewhere)
        TicketPhotoStore.clearAll(context)
        assertFalse(photo.exists())
        assertTrue(elsewhere.exists())
        assertTrue(unowned.exists())
    }

    @Test fun refusesEmptyAndInvalidCameraOutput() {
        val raw = TicketPhotoStore.createCapture(context)
        assertThrows(IllegalArgumentException::class.java) { TicketPhotoStore.prepare(context, raw) }
        raw.writeText("not an image")
        assertThrows(IllegalArgumentException::class.java) { TicketPhotoStore.prepare(context, raw) }
    }

    @Test fun resizesLargePhotosAndProducesReadableJpegPreview() {
        val raw = image(3200, 2400)
        val photo = TicketPhotoStore.prepare(context, raw)
        val decoded = BitmapFactory.decodeFile(photo.path)
        assertEquals(1600, decoded.width)
        assertEquals(1200, decoded.height)
        decoded.recycle()
        val preview = TicketPhotoStore.preview(photo)
        assertTrue(preview.width <= 1600)
        preview.recycle()
        assertTrue(photo.length() in 1..(4L * 1024 * 1024))
    }

    @Test fun correctsRotationAndDropsLocationAndCameraMetadata() {
        val raw = image(400, 200)
        ExifInterface(raw.path).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            setAttribute(ExifInterface.TAG_GPS_LATITUDE, "49/1,0/1,0/1")
            setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, "N")
            setAttribute(ExifInterface.TAG_MAKE, "Test camera")
            saveAttributes()
        }
        val photo = TicketPhotoStore.prepare(context, raw)
        val decoded = BitmapFactory.decodeFile(photo.path)
        assertEquals(200, decoded.width)
        assertEquals(400, decoded.height)
        decoded.recycle()
        ExifInterface(photo.path).also {
            assertNull(it.getAttribute(ExifInterface.TAG_GPS_LATITUDE))
            assertNull(it.getAttribute(ExifInterface.TAG_MAKE))
            assertNotEquals(ExifInterface.ORIENTATION_ROTATE_90,
                it.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL))
        }
    }

    @Test fun samplingIsBoundedAndInvalidDimensionsAreRejected() {
        assertEquals(1, TicketPhotoStore.sampleSize(1600, 1200, 1600))
        assertEquals(4, TicketPhotoStore.sampleSize(12000, 9000, 1600))
        assertThrows(IllegalArgumentException::class.java) { TicketPhotoStore.sampleSize(0, 2, 800) }
    }
}
