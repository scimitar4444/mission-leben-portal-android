package de.missionleben.portal.web

import android.app.Application
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowDownloadManager.ShadowRequest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PersonalDownloadsTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Before fun clearRecords() {
        context.getSharedPreferences(PersonalDownloads.PREFERENCES, Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    private fun download(status: Int): Long {
        val manager = context.getSystemService(DownloadManager::class.java)
        val request = DownloadManager.Request(Uri.parse("https://dokumente.mission-leben.de/files/1/download"))
            .setTitle("Dokument.pdf").setMimeType("application/pdf")
        val id = manager.enqueue(request)
        Shadow.extract<ShadowRequest>(request).setStatus(status)
        PersonalDownloads.record(context, id)
        return id
    }

    @Test fun `logout retains successful exports and cancels unfinished downloads`() {
        val manager = context.getSystemService(DownloadManager::class.java)
        val complete = download(DownloadManager.STATUS_SUCCESSFUL)
        val pending = download(DownloadManager.STATUS_PENDING)
        val running = download(DownloadManager.STATUS_RUNNING)
        val paused = download(DownloadManager.STATUS_PAUSED)
        assertEquals(DownloadManager.STATUS_SUCCESSFUL, PersonalDownloads.status(context, complete))

        PersonalDownloads.cancelPending(context)

        val shadow = Shadows.shadowOf(manager)
        assertNotNull(shadow.getRequest(complete))
        listOf(pending, running, paused).forEach { assertNull(shadow.getRequest(it)) }
        assertTrue(PersonalDownloads.pending(context).isEmpty())
    }

    @Test fun `only app owned download ids are queried or removed`() {
        val manager = context.getSystemService(DownloadManager::class.java)
        val unrelated = manager.enqueue(DownloadManager.Request(Uri.parse("https://example.org/file")))
        assertNull(PersonalDownloads.status(context, unrelated))
        PersonalDownloads.cancelPending(context)
        assertNotNull(Shadows.shadowOf(manager).getRequest(unrelated))
    }

    @Test fun `viewer receives a file content uri and read access without portal data`() {
        val uri = Uri.parse("content://downloads/all_downloads/42")
        val intent = requireNotNull(PersonalDownloads.viewIntent(uri, "application/pdf; charset=utf-8"))
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(uri, intent.data)
        assertEquals("application/pdf", intent.type)
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, intent.flags)
        assertEquals(uri, intent.clipData?.getItemAt(0)?.uri)
        assertNull(intent.extras)
        assertNull(PersonalDownloads.viewIntent(Uri.parse("https://dokumente.mission-leben.de/files/1/download"), "application/pdf"))
        assertNull(PersonalDownloads.viewIntent(Uri.parse("file:///sdcard/secret.pdf"), "application/pdf"))
        assertNull(PersonalDownloads.viewIntent(Uri.parse("content://foreign.provider/42"), "application/pdf"))
    }

    @Test fun `generic server mime uses filename so pdf opens in a pdf viewer`() {
        assertEquals("application/pdf", PersonalDownloads.mimeType("application/octet-stream", "Abrechnung.PDF"))
        assertEquals("text/plain", PersonalDownloads.mimeType("text/plain; charset=utf-8", "Dokument.txt"))
        assertEquals("application/octet-stream", PersonalDownloads.mimeType(null, "unknown"))
    }

    @Test fun `repeat downloads keep their extension and cannot overwrite one another`() {
        val first = PersonalDownloads.uniqueFilename("../Übersicht.pdf")
        val second = PersonalDownloads.uniqueFilename("../Übersicht.pdf")
        assertFalse(first.contains('/'))
        assertFalse(first.startsWith('.'))
        assertTrue(first.startsWith("_Übersicht-"))
        assertTrue(first.endsWith(".pdf"))
        assertNotEquals(first, second)
    }
}
