package de.missionleben.portal.web

import android.app.AlertDialog
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.view.WindowManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient.FileChooserParams
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import de.missionleben.portal.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URI

/** Adds a native camera choice to the existing OTOBO attachment field, not to website media APIs. */
internal class PortalFileChooser(
    private val activity: FragmentActivity,
    private val currentPage: () -> String,
    private val mayReturnFiles: () -> Boolean,
    private val mayUseTicketPhoto: () -> Boolean,
) {
    private var callback: ValueCallback<Array<Uri>>? = null
    private var requestPage = ""
    private var dialog: AlertDialog? = null
    private var capture: File? = null
    private var processing: Job? = null
    private var externalPickerOpen = false
    private val retainedPhotos = mutableSetOf<File>()

    private val files = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        externalPickerOpen = false
        val values = runCatching {
            FileChooserParams.parseResult(result.resultCode, result.data)?.filter { uri ->
                runCatching { TicketAttachmentPolicy.isExternalSelectedFile(URI(uri.toString()), activity.packageName) }.getOrDefault(false)
            }?.toTypedArray()?.takeIf { it.isNotEmpty() }
        }.getOrNull()
        deliver(values)
    }
    private val camera = activity.registerForActivityResult(object : ActivityResultContracts.TakePicture() {
        override fun createIntent(context: Context, input: Uri): Intent = super.createIntent(context, input).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            clipData = ClipData.newRawUri("Ticket photo", input)
        }
    }) { success ->
        externalPickerOpen = false
        val raw = capture
        capture = null
        raw?.let { runCatching { activity.revokeUriPermission(TicketPhotoStore.uri(activity, it),
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) } }
        if (!success || raw == null || !photoRequestValid()) {
            TicketPhotoStore.discard(activity, raw)
            deliver(null)
        } else {
            processing = activity.lifecycleScope.launch {
                var photo: File? = null
                var bitmap: Bitmap? = null
                var previewOwnsPhoto = false
                try {
                    withContext(Dispatchers.IO) {
                        photo = TicketPhotoStore.prepare(activity, raw)
                        bitmap = TicketPhotoStore.preview(requireNotNull(photo))
                    }
                    if (photoRequestValid()) {
                        showPreview(requireNotNull(photo), requireNotNull(bitmap))
                        previewOwnsPhoto = true
                    } else deliver(null)
                } catch (_: Exception) {
                    if (callback != null && !activity.isDestroyed) {
                        Toast.makeText(activity, R.string.ticket_photo_failed, Toast.LENGTH_LONG).show()
                        deliver(null)
                    }
                } finally {
                    TicketPhotoStore.discard(activity, raw)
                    if (!previewOwnsPhoto) {
                        bitmap?.recycle()
                        TicketPhotoStore.discard(activity, photo)
                    }
                }
            }
        }
    }

    fun show(fileCallback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
        // A second website request must not steal the result of an already-running picker/camera.
        if (callback != null || externalPickerOpen) { fileCallback.onReceiveValue(null); return true }
        callback = fileCallback
        requestPage = currentPage()
        if (!requestValid()) { deliver(null); return true }
        if (mayUseTicketPhoto() && TicketAttachmentPolicy.acceptsJpeg(params.acceptTypes, params.mode)) {
            showSecure(AlertDialog.Builder(activity).setTitle(R.string.ticket_attachment_title)
                .setItems(arrayOf(activity.getString(R.string.ticket_photo_take), activity.getString(R.string.ticket_file_choose))) { _, which ->
                    if (which == 0) takePhoto() else chooseFile(params)
                }.setOnCancelListener { deliver(null) }.create())
        } else chooseFile(params)
        return true
    }

    private fun chooseFile(params: FileChooserParams) {
        if (!requestValid()) { deliver(null); return }
        runCatching {
            val intent = params.createIntent()
            externalPickerOpen = true
            files.launch(intent)
        }.onFailure {
            externalPickerOpen = false
            Toast.makeText(activity, R.string.ticket_file_failed, Toast.LENGTH_LONG).show()
            deliver(null)
        }
    }

    private fun takePhoto() {
        if (!photoRequestValid()) { deliver(null); return }
        runCatching {
            capture = TicketPhotoStore.createCapture(activity)
            externalPickerOpen = true
            camera.launch(TicketPhotoStore.uri(activity, requireNotNull(capture)))
        }.onFailure {
            externalPickerOpen = false
            TicketPhotoStore.discard(activity, capture)
            capture = null
            Toast.makeText(activity, R.string.ticket_camera_missing, Toast.LENGTH_LONG).show()
            deliver(null)
        }
    }

    private fun showPreview(photo: File, bitmap: Bitmap) {
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(ImageView(activity).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                setImageBitmap(bitmap)
                contentDescription = activity.getString(R.string.ticket_photo_preview)
            }, LinearLayout.LayoutParams(-1, dp(230)))
            addView(TextView(activity).apply {
                text = activity.getString(R.string.ticket_photo_hint)
                setPadding(0, dp(12), 0, dp(8))
            })
        }
        var retained = false
        var retake = false
        val preview = AlertDialog.Builder(activity).setTitle(R.string.ticket_photo_preview).setView(content)
            .setPositiveButton(R.string.ticket_photo_use) { _, _ ->
                if (photoRequestValid()) {
                    runCatching { TicketPhotoStore.uri(activity, photo) }.onSuccess { uri ->
                        retained = true
                        retainedPhotos += photo
                        deliver(arrayOf(uri))
                    }.onFailure {
                        Toast.makeText(activity, R.string.ticket_photo_failed, Toast.LENGTH_LONG).show()
                        deliver(null)
                    }
                } else deliver(null)
            }.setNeutralButton(R.string.ticket_photo_retake) { _, _ -> retake = true }
            .setNegativeButton(R.string.ticket_photo_discard) { _, _ -> deliver(null) }
            .setOnCancelListener { deliver(null) }.create()
        preview.setOnDismissListener {
            (content.getChildAt(0) as ImageView).setImageDrawable(null)
            bitmap.recycle()
            if (!retained) TicketPhotoStore.discard(activity, photo)
            if (retake) takePhoto()
        }
        showSecure(preview)
    }

    private fun showSecure(value: AlertDialog) {
        dialog = value
        value.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        value.show()
        value.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    private fun requestValid() = callback != null && !activity.isFinishing && !activity.isDestroyed &&
        mayReturnFiles() && TicketAttachmentPolicy.samePage(requestPage, currentPage())

    private fun photoRequestValid() = requestValid() && mayUseTicketPhoto()

    private fun deliver(values: Array<Uri>?) {
        val destination = callback ?: return
        val safe = if (requestValid()) values else null
        callback = null
        destination.onReceiveValue(safe)
    }

    fun pageChanged(url: String) {
        if (callback != null && !TicketAttachmentPolicy.samePage(requestPage, url)) cancel()
    }

    fun cancel() {
        val destination = callback
        callback = null
        dialog?.dismiss()
        dialog = null
        processing?.cancel()
        TicketPhotoStore.discard(activity, capture)
        capture = null
        destination?.onReceiveValue(null)
    }

    fun destroy() {
        cancel()
        retainedPhotos.forEach { TicketPhotoStore.discard(activity, it) }
        retainedPhotos.clear()
    }

    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()
}
