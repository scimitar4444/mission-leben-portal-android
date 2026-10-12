package de.missionleben.portal.web

import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.graphics.Bitmap
import android.os.Looper
import android.view.WindowManager
import android.webkit.WebChromeClient.FileChooserParams
import androidx.fragment.app.FragmentActivity
import androidx.core.content.FileProvider
import de.missionleben.portal.R
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.GraphicsMode
import org.robolectric.android.controller.ActivityController
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.util.ReflectionHelpers
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, shadows = [PickerResultShadow::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PortalFileChooserTest {
    private lateinit var controller: ActivityController<FragmentActivity>
    private lateinit var activity: FragmentActivity
    private lateinit var chooser: PortalFileChooser
    private var page = "https://it-service.mission-leben.de:8443/otobo/customer.pl?Action=CustomerTicketMessage"
    private var allowed = true
    private var photoAllowed = true
    private var callbacks = 0
    private var received: Array<Uri>? = null
    private fun params(types: Array<String> = emptyArray()) = object : FileChooserParams() {
        override fun getMode() = MODE_OPEN_MULTIPLE
        override fun getAcceptTypes() = types
        override fun isCaptureEnabled() = false
        override fun getTitle(): CharSequence? = null
        override fun getFilenameHint(): String? = null
        override fun createIntent() = Intent(Intent.ACTION_GET_CONTENT).setType("*/*")
    }
    private fun show(types: Array<String> = emptyArray()) = chooser.show({ value -> callbacks++; received = value }, params(types))

    @Before fun setup() {
        ReflectionHelpers.getStaticField<MutableMap<String, Any>>(FileProvider::class.java, "sCache").clear()
        TicketPhotoStore.clearAll(RuntimeEnvironment.getApplication())
        controller = Robolectric.buildActivity(FragmentActivity::class.java).create()
        activity = controller.get()
        chooser = PortalFileChooser(activity, { page }, { allowed }, { photoAllowed })
        controller.start().resume().visible()
    }
    @After fun teardown() { chooser.destroy(); controller.pause().stop().destroy() }

    @Test fun addsTwoChoicesAndProtectsTheDialogFromScreenshots() {
        show()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        assertEquals(activity.getString(R.string.ticket_photo_take), dialog.listView.adapter.getItem(0))
        assertEquals(activity.getString(R.string.ticket_file_choose), dialog.listView.adapter.getItem(1))
        assertTrue(dialog.window!!.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
        dialog.cancel()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, callbacks)
        assertNull(received)
    }

    @Test fun otherApplicationsAndIncompatibleFieldsKeepTheNormalPicker() {
        photoAllowed = false
        show()
        val request = Shadows.shadowOf(activity).nextStartedActivityForResult
        assertEquals(Intent.ACTION_GET_CONTENT, request.intent.action)
        activity.activityResultRegistry.dispatchResult(request.requestCode, Activity.RESULT_CANCELED, null)
        photoAllowed = true
        show(arrayOf("application/pdf"))
        assertEquals(Intent.ACTION_GET_CONTENT, Shadows.shadowOf(activity).nextStartedActivityForResult.intent.action)
    }

    @Test fun cameraGetsOnlyThePrivateOutputUriAndCancellationDeletesIt() {
        show()
        ShadowAlertDialog.getLatestAlertDialog().listView.performItemClick(null, 0, 0)
        val request = Shadows.shadowOf(activity).nextStartedActivityForResult
        assertEquals("android.media.action.IMAGE_CAPTURE", request.intent.action)
        val uri = request.intent.clipData!!.getItemAt(0).uri
        assertEquals("${activity.packageName}.ticket-photos", uri.authority)
        assertTrue(request.intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0)
        activity.activityResultRegistry.dispatchResult(request.requestCode, Activity.RESULT_CANCELED, null)
        assertEquals(1, callbacks)
        assertNull(received)
        assertTrue(java.io.File(activity.cacheDir, "ticket-photos").listFiles().orEmpty().none { it.name.startsWith("capture-") })
    }

    @Test fun navigationCancelsPendingSelectionAndALateResultCannotStealANewRequest() {
        photoAllowed = false
        show()
        val request = Shadows.shadowOf(activity).nextStartedActivityForResult
        page = page.replace("Message", "Overview")
        chooser.pageChanged(page)
        assertEquals(1, callbacks)
        var rejected = false
        chooser.show({ value -> rejected = value == null }, params())
        assertTrue(rejected)
        activity.activityResultRegistry.dispatchResult(request.requestCode, Activity.RESULT_OK,
            Intent().setData(Uri.parse("content://media/photos/42")))
        assertEquals(1, callbacks)
    }

    @Test fun lockRejectsTheResultEvenWhenTheUrlDidNotChange() {
        photoAllowed = false
        show()
        val request = Shadows.shadowOf(activity).nextStartedActivityForResult
        allowed = false
        activity.activityResultRegistry.dispatchResult(request.requestCode, Activity.RESULT_OK,
            Intent().setData(Uri.parse("content://media/photos/42")))
        assertEquals(1, callbacks)
        assertNull(received)
    }

    @Test fun normalPickerAcceptsExternalContentButRejectsAnOwnPrivateUri() {
        photoAllowed = false
        show()
        var request = Shadows.shadowOf(activity).nextStartedActivityForResult
        val external = Uri.parse("content://media/photos/42")
        activity.activityResultRegistry.dispatchResult(request.requestCode, Activity.RESULT_OK, Intent().setData(external))
        assertArrayEquals(arrayOf(external), received)
        show()
        request = Shadows.shadowOf(activity).nextStartedActivityForResult
        activity.activityResultRegistry.dispatchResult(request.requestCode, Activity.RESULT_OK,
            Intent().setData(Uri.parse("content://${activity.packageName}.files/updates/app.apk")))
        assertNull(received)
    }

    private fun captureAndPreview(): AlertDialog {
        show()
        ShadowAlertDialog.getLatestAlertDialog().listView.performItemClick(null, 0, 0)
        val request = Shadows.shadowOf(activity).nextStartedActivityForResult
        val uri = request.intent.clipData!!.getItemAt(0).uri
        val bitmap = Bitmap.createBitmap(120, 80, Bitmap.Config.ARGB_8888)
        activity.contentResolver.openOutputStream(uri)!!.use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bitmap.recycle()
        activity.activityResultRegistry.dispatchResult(request.requestCode, Activity.RESULT_OK, null)
        val deadline = System.nanoTime() + 10_000_000_000L
        while (System.nanoTime() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            if (dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.text == activity.getString(R.string.ticket_photo_use)) return dialog
            Thread.sleep(10)
        }
        error("Photo preview was not displayed")
    }

    @Test fun previewIsRequiredBeforeAnyPhotoIsReturnedAndDiscardDeletesIt() {
        val preview = captureAndPreview()
        assertEquals(0, callbacks)
        assertTrue(preview.window!!.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
        preview.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, callbacks)
        assertNull(received)
        assertTrue(java.io.File(activity.cacheDir, "ticket-photos").listFiles().orEmpty().isEmpty())
    }

    @Test fun confirmationReturnsOneProcessedPhotoAndBrowserCloseRemovesItsTemporaryCopy() {
        val preview = captureAndPreview()
        preview.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, callbacks)
        val uri = received!!.single()
        assertEquals("${activity.packageName}.ticket-photos", uri.authority)
        assertTrue(uri.lastPathSegment!!.startsWith("ticket-photo-"))
        assertNotNull(activity.contentResolver.openInputStream(uri)?.use { it.read() })
        chooser.destroy()
        assertTrue(java.io.File(activity.cacheDir, "ticket-photos").listFiles().orEmpty().isEmpty())
    }

    @Test fun retakeDiscardsTheFirstPhotoWithoutReturningItToTheWebsite() {
        val preview = captureAndPreview()
        preview.getButton(AlertDialog.BUTTON_NEUTRAL).performClick()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, callbacks)
        assertTrue(java.io.File(activity.cacheDir, "ticket-photos").listFiles().orEmpty().none { it.name.startsWith("ticket-photo-") })
        val request = Shadows.shadowOf(activity).nextStartedActivityForResult
        assertEquals("android.media.action.IMAGE_CAPTURE", request.intent.action)
        activity.activityResultRegistry.dispatchResult(request.requestCode, Activity.RESULT_CANCELED, null)
        assertNull(received)
        assertEquals(1, callbacks)
    }

    @Test fun aLockDuringThePreviewPreventsUploadingAndDeletesThePhoto() {
        val preview = captureAndPreview()
        allowed = false
        preview.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, callbacks)
        assertNull(received)
        assertTrue(java.io.File(activity.cacheDir, "ticket-photos").listFiles().orEmpty().isEmpty())
    }

    @Test fun allSupportedLanguagesHaveTranslatedPhotoInstructions() {
        val application = RuntimeEnvironment.getApplication()
        val german = application.createConfigurationContext(Configuration(application.resources.configuration).apply {
            setLocale(Locale.GERMAN)
        })
        for (language in listOf("de", "en", "es", "fr", "hi", "pl", "ro", "tr", "uk")) {
            val context = application.createConfigurationContext(Configuration(application.resources.configuration).apply {
                setLocale(Locale.forLanguageTag(language))
            })
            for (id in listOf(R.string.ticket_photo_take, R.string.ticket_photo_hint, R.string.ticket_photo_use,
                R.string.ticket_photo_failed, R.string.ticket_camera_missing)) {
                assertTrue(language, context.getString(id).isNotBlank())
                if (language != "de") assertNotEquals(language, german.getString(id), context.getString(id))
            }
        }
    }
}

/** The real method delegates to the installed WebView provider, absent from Robolectric. */
@Implements(FileChooserParams::class)
class PickerResultShadow {
    companion object {
        @JvmStatic @Implementation
        fun parseResult(resultCode: Int, data: Intent?): Array<Uri>? {
            if (resultCode != Activity.RESULT_OK || data == null) return null
            return data.clipData?.let { clip -> Array(clip.itemCount) { clip.getItemAt(it).uri } }
                ?: data.data?.let { arrayOf(it) }
        }
    }
}
