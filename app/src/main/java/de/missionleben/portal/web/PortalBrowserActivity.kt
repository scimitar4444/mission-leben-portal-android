package de.missionleben.portal.web

import android.Manifest
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebViewDatabase
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import de.missionleben.portal.BuildConfig
import de.missionleben.portal.R
import de.missionleben.portal.model.DeviceMode
import java.io.File

class PortalBrowserActivity : FragmentActivity() {
    private lateinit var webView: WebView
    private lateinit var progress: ProgressBar
    private lateinit var titleView: TextView
    private lateinit var policy: WebNavigationPolicy
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var pendingPermissionRequest: PermissionRequest? = null
    private var logoutFinished = false

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val callback = fileCallback ?: return@registerForActivityResult
        fileCallback = null
        val values = WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
            ?.filter { uri -> uri.scheme == "content" }
            ?.toTypedArray()
        callback.onReceiveValue(values)
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        val request = pendingPermissionRequest ?: return@registerForActivityResult
        pendingPermissionRequest = null
        val allowed = request.resources.filter { resource ->
            when (resource) {
                PermissionRequest.RESOURCE_VIDEO_CAPTURE -> grants[Manifest.permission.CAMERA] == true
                PermissionRequest.RESOURCE_AUDIO_CAPTURE -> grants[Manifest.permission.RECORD_AUDIO] == true
                else -> false
            }
        }
        if (allowed.isEmpty()) request.deny() else request.grant(allowed.toTypedArray())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)

        val startUrl = intent.getStringExtra(EXTRA_URL).orEmpty()
        val redirectUri = intent.getStringExtra(EXTRA_REDIRECT_URI).orEmpty()
        policy = WebNavigationPolicy(BuildConfig.WEB_ALLOWED_HOST_SUFFIXES, redirectUri)
        if (!policy.isTrustedWebUrl(startUrl)) {
            Toast.makeText(this, "Diese Adresse ist für den geschützten Bereich nicht freigegeben.", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        if (WebViewCompat.getCurrentWebViewPackage(this) == null) {
            Toast.makeText(this, "Android System WebView fehlt oder ist deaktiviert.", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        buildLayout(intent.getStringExtra(EXTRA_TITLE).orEmpty())
        configureWebView()
        installBackNavigation()

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState)
        } else if (intent.getBooleanExtra(EXTRA_CLEAR_BEFORE_LOAD, false)) {
            clearLocalWebData(this) { webView.loadUrl(startUrl) }
        } else {
            webView.loadUrl(startUrl)
        }
    }

    private fun buildLayout(initialTitle: String) {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(250, 247, 252))
        }
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(12), 0)
            setBackgroundColor(Color.rgb(42, 31, 48))
        }
        val close = TextView(this).apply {
            text = getString(R.string.browser_back)
            textSize = 16f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(8), 0, dp(12), 0)
            setOnClickListener { finish() }
        }
        titleView = TextView(this).apply {
            text = initialTitle.ifBlank { "Geschützter Bereich" }
            textSize = 16f
            setTextColor(Color.WHITE)
            maxLines = 1
            gravity = Gravity.CENTER_VERTICAL
        }
        toolbar.addView(close, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(56)))
        toolbar.addView(titleView, LinearLayout.LayoutParams(0, dp(56), 1f))

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
        }
        webView = WebView(this).apply {
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_YES
        }
        root.addView(toolbar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)))
        root.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)))
        root.addView(webView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    @Suppress("SetJavaScriptEnabled")
    private fun configureWebView() {
        WebView.setWebContentsDebuggingEnabled(false)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            mediaPlaybackRequiresUserGesture = false
            saveFormData = false
            setGeolocationEnabled(false)
            userAgentString = "$userAgentString MissionLebenPortal/${BuildConfig.VERSION_NAME}"
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_AUTHENTICATION)) {
            WebSettingsCompat.setWebAuthenticationSupport(
                webView.settings,
                WebSettingsCompat.WEB_AUTHENTICATION_SUPPORT_FOR_APP,
            )
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, false)
        }
        webView.webViewClient = BrowserClient()
        webView.webChromeClient = BrowserChromeClient()
        webView.setDownloadListener(SecureDownloadListener())
    }

    private fun installBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack() else finish()
            }
        })
    }

    private inner class BrowserClient : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
            handleNavigation(request.url.toString(), request.isForMainFrame)

        @Deprecated("Compatibility for old WebView providers")
        override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean = handleNavigation(url, true)

        override fun onPageFinished(view: WebView, url: String) {
            super.onPageFinished(view, url)
            titleView.text = view.title?.takeIf { it.isNotBlank() } ?: titleView.text
            if (intent.getBooleanExtra(EXTRA_LOGOUT, false) && !logoutFinished) {
                logoutFinished = true
                clearLocalWebData(this@PortalBrowserActivity) { finish() }
            }
        }

        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: android.net.http.SslError) {
            handler.cancel()
            Toast.makeText(this@PortalBrowserActivity, "Die sichere Verbindung wurde abgebrochen.", Toast.LENGTH_LONG).show()
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            super.onReceivedError(view, request, error)
            if (request.isForMainFrame) {
                Toast.makeText(this@PortalBrowserActivity, "Die Seite konnte nicht geladen werden.", Toast.LENGTH_SHORT).show()
            }
        }

        override fun onSafeBrowsingHit(
            view: WebView,
            request: WebResourceRequest,
            threatType: Int,
            callback: android.webkit.SafeBrowsingResponse,
        ) {
            callback.backToSafety(true)
            Toast.makeText(this@PortalBrowserActivity, "Unsichere Seite wurde blockiert.", Toast.LENGTH_LONG).show()
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            Toast.makeText(this@PortalBrowserActivity, "Der geschützte Browser wurde beendet.", Toast.LENGTH_LONG).show()
            finish()
            return true
        }
    }

    private fun handleNavigation(url: String, isMainFrame: Boolean): Boolean {
        if (!isMainFrame) return false
        if (policy.isAuthorizationRedirect(url)) {
            setResult(RESULT_OK, Intent().setData(Uri.parse(url)))
            finish()
            return true
        }
        if (policy.isTrustedWebUrl(url)) return false
        if (policy.canOpenExternally(url)) {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        } else {
            Toast.makeText(this, "Dieser Link wurde blockiert.", Toast.LENGTH_SHORT).show()
        }
        return true
    }

    private inner class BrowserChromeClient : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) {
            progress.progress = newProgress
            progress.visibility = if (newProgress >= 100) View.GONE else View.VISIBLE
        }

        override fun onReceivedTitle(view: WebView, title: String?) {
            if (!title.isNullOrBlank()) titleView.text = title
        }

        override fun onPermissionRequest(request: PermissionRequest) {
            val origin = request.origin.toString()
            if (!policy.isTrustedWebUrl(origin)) {
                request.deny()
                return
            }
            val requestedResources = request.resources.filter {
                it == PermissionRequest.RESOURCE_VIDEO_CAPTURE || it == PermissionRequest.RESOURCE_AUDIO_CAPTURE
            }
            if (requestedResources.isEmpty()) {
                request.deny()
                return
            }
            val androidPermissions = buildList {
                if (PermissionRequest.RESOURCE_VIDEO_CAPTURE in requestedResources) add(Manifest.permission.CAMERA)
                if (PermissionRequest.RESOURCE_AUDIO_CAPTURE in requestedResources) add(Manifest.permission.RECORD_AUDIO)
            }
            val missing = androidPermissions.filter {
                ContextCompat.checkSelfPermission(this@PortalBrowserActivity, it) != PackageManager.PERMISSION_GRANTED
            }
            if (missing.isEmpty()) {
                request.grant(requestedResources.toTypedArray())
            } else {
                pendingPermissionRequest?.deny()
                pendingPermissionRequest = request
                permissionLauncher.launch(missing.toTypedArray())
            }
        }

        override fun onPermissionRequestCanceled(request: PermissionRequest) {
            if (pendingPermissionRequest == request) pendingPermissionRequest = null
        }

        override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback) {
            callback.invoke(origin, false, false)
        }

        override fun onShowFileChooser(
            webView: WebView,
            filePathCallback: ValueCallback<Array<Uri>>,
            fileChooserParams: FileChooserParams,
        ): Boolean {
            if (!policy.isTrustedWebUrl(webView.url.orEmpty())) return false
            fileCallback?.onReceiveValue(null)
            fileCallback = filePathCallback
            return runCatching {
                fileChooserLauncher.launch(fileChooserParams.createIntent())
                true
            }.getOrElse {
                fileCallback = null
                filePathCallback.onReceiveValue(null)
                false
            }
        }
    }

    private inner class SecureDownloadListener : DownloadListener {
        override fun onDownloadStart(
            url: String,
            userAgent: String,
            contentDisposition: String,
            mimeType: String,
            contentLength: Long,
        ) {
            if (!policy.isTrustedWebUrl(url)) {
                Toast.makeText(this@PortalBrowserActivity, "Download von einer nicht freigegebenen Domain blockiert.", Toast.LENGTH_LONG).show()
                return
            }
            val filename = URLUtil.guessFileName(url, contentDisposition, mimeType)
                .replace(Regex("[^A-Za-z0-9._ -]"), "_")
                .take(120)
                .ifBlank { "Download" }
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setMimeType(mimeType)
                addRequestHeader("User-Agent", userAgent)
                CookieManager.getInstance().getCookie(url)?.let { addRequestHeader("Cookie", it) }
                setTitle(filename)
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalFilesDir(this@PortalBrowserActivity, Environment.DIRECTORY_DOWNLOADS, filename)
            }
            val manager = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
            runCatching { manager.enqueue(request) }
                .onSuccess { downloadId ->
                    recordDownload(this@PortalBrowserActivity, downloadId)
                    Toast.makeText(this@PortalBrowserActivity, "Download wurde geschützt gespeichert.", Toast.LENGTH_SHORT).show()
                }
                .onFailure { Toast.makeText(this@PortalBrowserActivity, "Download konnte nicht gestartet werden.", Toast.LENGTH_LONG).show() }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (::webView.isInitialized) webView.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        fileCallback?.onReceiveValue(null)
        pendingPermissionRequest?.deny()
        if (::webView.isInitialized) {
            webView.stopLoading()
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.destroy()
        }
        super.onDestroy()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val EXTRA_URL = "url"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_REDIRECT_URI = "redirect_uri"
        private const val EXTRA_CLEAR_BEFORE_LOAD = "clear_before_load"
        private const val EXTRA_LOGOUT = "logout"
        private const val DOWNLOAD_PREFERENCES = "protected_web_downloads"
        private const val DOWNLOAD_IDS = "download_ids"

        fun appIntent(context: Context, url: String, title: String = "Web-Anwendung"): Intent =
            Intent(context, PortalBrowserActivity::class.java)
                .putExtra(EXTRA_URL, url)
                .putExtra(EXTRA_TITLE, title)

        fun authorizationIntent(context: Context, url: String, mode: DeviceMode): Intent =
            Intent(context, PortalBrowserActivity::class.java)
                .putExtra(EXTRA_URL, url)
                .putExtra(EXTRA_TITLE, "Sicher anmelden")
                .putExtra(EXTRA_REDIRECT_URI, BuildConfig.OIDC_REDIRECT_URI)
                .putExtra(EXTRA_CLEAR_BEFORE_LOAD, mode == DeviceMode.SHARED)

        fun logoutIntent(context: Context, url: String): Intent =
            Intent(context, PortalBrowserActivity::class.java)
                .putExtra(EXTRA_URL, url)
                .putExtra(EXTRA_TITLE, "Sitzung wird beendet")
                .putExtra(EXTRA_LOGOUT, true)

        fun clearLocalWebData(context: Context, onComplete: (() -> Unit)? = null) {
            CookieManager.getInstance().removeAllCookies {
                CookieManager.getInstance().flush()
                WebStorage.getInstance().deleteAllData()
                WebViewDatabase.getInstance(context).apply {
                    clearHttpAuthUsernamePassword()
                    clearFormData()
                }
                runCatching {
                    WebView(context.applicationContext).apply {
                        clearCache(true)
                        clearHistory()
                        clearFormData()
                        destroy()
                    }
                }
                cancelDownloads(context)
                context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                    ?.takeIf(File::exists)
                    ?.deleteRecursively()
                onComplete?.invoke()
            }
        }

        private fun recordDownload(context: Context, id: Long) {
            val preferences = context.getSharedPreferences(DOWNLOAD_PREFERENCES, Context.MODE_PRIVATE)
            val ids = preferences.getStringSet(DOWNLOAD_IDS, emptySet()).orEmpty().toMutableSet()
            ids += id.toString()
            preferences.edit().putStringSet(DOWNLOAD_IDS, ids).apply()
        }

        private fun cancelDownloads(context: Context) {
            val preferences = context.getSharedPreferences(DOWNLOAD_PREFERENCES, Context.MODE_PRIVATE)
            val ids = preferences.getStringSet(DOWNLOAD_IDS, emptySet()).orEmpty()
                .mapNotNull(String::toLongOrNull)
                .toLongArray()
            if (ids.isNotEmpty()) {
                runCatching { (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).remove(*ids) }
            }
            preferences.edit().clear().apply()
        }
    }
}
