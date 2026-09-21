package de.missionleben.portal.web

import android.app.DownloadManager
import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.util.Log
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
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.FragmentActivity
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import de.missionleben.portal.BuildConfig
import de.missionleben.portal.R
import de.missionleben.portal.device.DeviceServiceRepository
import de.missionleben.portal.device.EnrollmentQrParser
import de.missionleben.portal.model.DeviceMode
import de.missionleben.portal.model.EnrollmentState
import de.missionleben.portal.push.PushEventDispatcher
import org.json.JSONObject
import java.io.File

class PortalBrowserActivity : FragmentActivity() {
    private lateinit var webView: WebView
    private lateinit var progress: ProgressBar
    private lateinit var titleView: TextView
    private lateinit var policy: WebNavigationPolicy
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var logoutFinished = false
    private var endpointBridgeInstalled = false
    private var authorizationResultDelivered = false
    private var selfEnrollmentResultDelivered = false
    private var sessionExpiredResultDelivered = false
    private var talkChatNoticeShown = false
    private val deviceService by lazy { DeviceServiceRepository(applicationContext) }
    private val sessionPolicy by lazy {
        WebSessionPolicy(
            BuildConfig.AUTHENTIK_BASE_URL,
            BuildConfig.AUTHENTIK_AUTHENTICATION_FLOW_SLUGS,
        )
    }
    private val authentikOrigin by lazy {
        Uri.parse(BuildConfig.AUTHENTIK_BASE_URL).let { "${it.scheme}://${it.authority}" }
    }
    private val deviceMode by lazy {
        intent.getStringExtra(EXTRA_DEVICE_MODE)?.let { value ->
            runCatching { DeviceMode.valueOf(value) }.getOrNull()
        }
    }
    private val securityStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (
                intent?.action == PushEventDispatcher.ACTION_SECURITY_STATE_CHANGED &&
                intent.getStringExtra(PushEventDispatcher.EXTRA_ENROLLMENT_STATE) == EnrollmentState.BLOCKED.name
            ) {
                setResult(RESULT_OK, Intent().putExtra(EXTRA_DEVICE_BLOCKED, true))
                finish()
            }
        }
    }

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)

        val startUrl = intent.getStringExtra(EXTRA_URL).orEmpty()
        val redirectUri = intent.getStringExtra(EXTRA_REDIRECT_URI).orEmpty()
        policy = WebNavigationPolicy(BuildConfig.WEB_ALLOWED_HOST_SUFFIXES, redirectUri)
        if (!policy.isTrustedWebUrl(startUrl)) {
            Toast.makeText(this, R.string.browser_address_blocked, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        if (WebViewCompat.getCurrentWebViewPackage(this) == null) {
            Toast.makeText(this, R.string.browser_webview_missing, Toast.LENGTH_LONG).show()
            finish()
            return
        }

        val initialTitle = if (TalkChatPolicy.isTalkPage(startUrl)) {
            getString(R.string.talk_chat_title)
        } else {
            intent.getStringExtra(EXTRA_TITLE).orEmpty()
        }
        buildLayout(initialTitle)
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

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(
            this,
            securityStateReceiver,
            IntentFilter(PushEventDispatcher.ACTION_SECURITY_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onStop() {
        unregisterReceiver(securityStateReceiver)
        super.onStop()
    }

    private fun buildLayout(initialTitle: String) {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ContextCompat.getColor(this@PortalBrowserActivity, R.color.ml_surface))
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(systemBars.left, systemBars.top + dp(5), systemBars.right, systemBars.bottom)
            insets
        }
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(12), 0)
            setBackgroundColor(Color.rgb(42, 31, 48))
        }
        val close = TextView(this).apply {
            text = getString(R.string.browser_back)
            textSize = 15f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(8), 0, dp(12), 0)
            setOnClickListener { finish() }
        }
        titleView = TextView(this).apply {
            text = initialTitle.ifBlank { getString(R.string.browser_protected_area) }
            textSize = 15f
            setTextColor(Color.WHITE)
            maxLines = 1
            gravity = Gravity.CENTER_VERTICAL
        }
        toolbar.addView(close, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)))
        toolbar.addView(titleView, LinearLayout.LayoutParams(0, dp(48), 1f))

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
        }
        webView = WebView(this).apply {
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_YES
            setBackgroundColor(ContextCompat.getColor(this@PortalBrowserActivity, R.color.ml_surface))
        }
        root.addView(toolbar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))
        root.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(2)))
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
            val modeMarker = deviceMode?.name?.lowercase() ?: "unknown"
            userAgentString = "$userAgentString MissionLebenPortal/${BuildConfig.VERSION_NAME} " +
                "MissionLebenMode/$modeMarker"
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_AUTHENTICATION)) {
            WebSettingsCompat.setWebAuthenticationSupport(
                webView.settings,
                WebSettingsCompat.WEB_AUTHENTICATION_SUPPORT_FOR_APP,
            )
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(webView.settings, true)
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, false)
        }
        webView.webViewClient = BrowserClient()
        webView.webChromeClient = BrowserChromeClient()
        webView.setDownloadListener(SecureDownloadListener())
        installEndpointChallengeBridge()
        installTalkChatGuard()
        installAnnouncementCenterGuard()
    }

    private fun installTalkChatGuard() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return
        WebViewCompat.addDocumentStartJavaScript(
            webView,
            TalkChatPolicy.CHAT_ONLY_SCRIPT,
            setOf(TalkChatPolicy.NEXTCLOUD_ORIGIN),
        )
    }

    private fun installAnnouncementCenterGuard() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return
        WebViewCompat.addDocumentStartJavaScript(
            webView,
            AnnouncementCenterPolicy.EMBEDDED_SCRIPT,
            setOf(AnnouncementCenterPolicy.NEXTCLOUD_ORIGIN),
        )
    }

    private fun installEndpointChallengeBridge() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return
        WebViewCompat.addWebMessageListener(
            webView,
            ENDPOINT_BRIDGE_NAME,
            setOf(authentikOrigin),
        ) { view, message, sourceOrigin, isMainFrame, _ ->
            if (!isMainFrame || !isAuthentikOrigin(sourceOrigin.toString())) return@addWebMessageListener
            if (!isAuthentikOrigin(view.url.orEmpty())) return@addWebMessageListener
            val challenge = message.data ?: return@addWebMessageListener
            val response = runCatching {
                deviceService.signEndpointChallenge(challenge)
            }.getOrNull() ?: return@addWebMessageListener
            view.post {
                if (!isAuthentikOrigin(view.url.orEmpty())) return@post
                val script = "window.postMessage({_ak_ext:'authentik-platform-sso',response:" +
                    JSONObject.quote(response) + "}," + JSONObject.quote(authentikOrigin) + ");"
                view.evaluateJavascript(script, null)
            }
        }
        endpointBridgeInstalled = true
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(
                webView,
                ENDPOINT_BRIDGE_SCRIPT,
                setOf(authentikOrigin),
            )
        }
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

        override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
            if (interceptExpiredSession(url)) return
            super.onPageStarted(view, url, favicon)
        }

        override fun onPageFinished(view: WebView, url: String) {
            super.onPageFinished(view, url)
            if (interceptExpiredSession(url)) return
            titleView.text = view.title?.takeIf { it.isNotBlank() } ?: titleView.text
            if (isAuthentikOrigin(url)) view.evaluateJavascript(ENDPOINT_BRIDGE_SCRIPT, null)
            if (TalkChatPolicy.isTalkPage(url)) {
                // Fallback for providers without document-start script support and defense in depth
                // after Nextcloud/Talk SPA route changes.
                view.evaluateJavascript(TalkChatPolicy.CHAT_ONLY_SCRIPT, null)
                if (!talkChatNoticeShown) {
                    talkChatNoticeShown = true
                    Toast.makeText(
                        this@PortalBrowserActivity,
                        R.string.talk_chat_only_notice,
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
            if (AnnouncementCenterPolicy.isAnnouncementPage(url)) {
                // Fallback for older WebView providers without document-start script support.
                view.evaluateJavascript(AnnouncementCenterPolicy.EMBEDDED_SCRIPT, null)
            }
            if (intent.getBooleanExtra(EXTRA_LOGOUT, false) && !logoutFinished) {
                logoutFinished = true
                clearLocalWebData(this@PortalBrowserActivity) { finish() }
            }
        }

        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: android.net.http.SslError) {
            handler.cancel()
            Toast.makeText(this@PortalBrowserActivity, R.string.browser_ssl_failed, Toast.LENGTH_LONG).show()
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            super.onReceivedError(view, request, error)
            if (request.isForMainFrame) {
                Toast.makeText(this@PortalBrowserActivity, R.string.browser_page_failed, Toast.LENGTH_SHORT).show()
            }
        }

        override fun onSafeBrowsingHit(
            view: WebView,
            request: WebResourceRequest,
            threatType: Int,
            callback: android.webkit.SafeBrowsingResponse,
        ) {
            callback.backToSafety(true)
            Toast.makeText(this@PortalBrowserActivity, R.string.browser_unsafe_blocked, Toast.LENGTH_LONG).show()
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            Toast.makeText(this@PortalBrowserActivity, R.string.browser_process_ended, Toast.LENGTH_LONG).show()
            finish()
            return true
        }
    }

    private fun isAuthentikOrigin(value: String): Boolean = runCatching {
        val uri = Uri.parse(value)
        val configured = Uri.parse(BuildConfig.AUTHENTIK_BASE_URL)
        uri.scheme == "https" &&
            uri.host.equals(configured.host, ignoreCase = true) &&
            uri.port == configured.port
    }.getOrDefault(false)

    private fun handleNavigation(url: String, isMainFrame: Boolean): Boolean {
        if (!isMainFrame) return false
        if (interceptExpiredSession(url)) return true
        if (intent.getBooleanExtra(EXTRA_SELF_ENROLLMENT, false)) {
            val enrollment = EnrollmentQrParser.parse(url)
            if (
                enrollment?.tokenUuid != null &&
                enrollment.mode == DeviceMode.PERSONAL &&
                !selfEnrollmentResultDelivered
            ) {
                selfEnrollmentResultDelivered = true
                setResult(RESULT_OK, Intent().setData(Uri.parse(url)))
                finish()
                return true
            }
        }
        if (policy.isAuthorizationRedirect(url)) {
            if (!authorizationResultDelivered) {
                authorizationResultDelivered = true
                val response = Uri.parse(url)
                Log.i(
                    AUTH_LOG_TAG,
                    "Authorization redirect received: parameters=${response.queryParameterNames.sorted()}",
                )
                setResult(
                    RESULT_OK,
                    Intent()
                        .setData(response)
                        .putExtra(EXTRA_AUTHORIZATION_RESPONSE, url),
                )
                finish()
            }
            return true
        }
        if (policy.isTrustedWebUrl(url)) return false
        if (policy.canOpenExternally(url)) {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        } else {
            Toast.makeText(this, R.string.browser_link_blocked, Toast.LENGTH_SHORT).show()
        }
        return true
    }

    private fun interceptExpiredSession(url: String): Boolean {
        val isApplicationBrowser = !intent.hasExtra(EXTRA_REDIRECT_URI) &&
            !intent.getBooleanExtra(EXTRA_LOGOUT, false) &&
            !intent.getBooleanExtra(EXTRA_SELF_ENROLLMENT, false)
        if (!isApplicationBrowser || !sessionPolicy.isInteractiveAuthentication(url)) return false
        if (!sessionExpiredResultDelivered) {
            sessionExpiredResultDelivered = true
            webView.stopLoading()
            setResult(RESULT_OK, Intent().putExtra(EXTRA_SESSION_EXPIRED, true))
            finish()
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
            // The app declares no camera or microphone permission. No website,
            // including an otherwise trusted portal application, receives
            // media access from this WebView.
            if (TalkChatPolicy.isTalkPage(webView.url.orEmpty())) {
                Toast.makeText(this@PortalBrowserActivity, R.string.talk_media_disabled, Toast.LENGTH_LONG).show()
            }
            request.deny()
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
                Toast.makeText(this@PortalBrowserActivity, R.string.browser_download_domain_blocked, Toast.LENGTH_LONG).show()
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
                    Toast.makeText(this@PortalBrowserActivity, R.string.browser_download_saved, Toast.LENGTH_SHORT).show()
                }
                .onFailure { Toast.makeText(this@PortalBrowserActivity, R.string.browser_download_failed, Toast.LENGTH_LONG).show() }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (::webView.isInitialized) webView.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        fileCallback?.onReceiveValue(null)
        if (::webView.isInitialized) {
            webView.stopLoading()
            if (endpointBridgeInstalled) {
                WebViewCompat.removeWebMessageListener(webView, ENDPOINT_BRIDGE_NAME)
            }
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
        private const val EXTRA_AUTHORIZATION_RESPONSE = "authorization_response"
        private const val EXTRA_SESSION_EXPIRED = "session_expired"
        private const val EXTRA_DEVICE_BLOCKED = "device_blocked"
        private const val EXTRA_DEVICE_MODE = "device_mode"
        private const val EXTRA_CLEAR_BEFORE_LOAD = "clear_before_load"
        private const val EXTRA_LOGOUT = "logout"
        private const val EXTRA_SELF_ENROLLMENT = "self_enrollment"
        private const val DOWNLOAD_PREFERENCES = "protected_web_downloads"
        private const val DOWNLOAD_IDS = "download_ids"
        private const val ENDPOINT_BRIDGE_NAME = "MissionLebenEndpoint"
        private const val AUTH_LOG_TAG = "MissionLebenAuth"
        private const val ENDPOINT_BRIDGE_SCRIPT = """
            (() => {
              if (window.__mlAuthentikEndpointInstalled) return;
              window.__mlAuthentikEndpointInstalled = true;
              const forward = (challenge) => {
                if (typeof challenge !== 'string' || challenge.length < 32 || challenge.length > 8192) return;
                if (window.MissionLebenEndpoint && window.MissionLebenEndpoint.postMessage) {
                  window.MissionLebenEndpoint.postMessage(challenge);
                }
              };
              window.addEventListener('message', (event) => {
                if (event.source !== window || !event.data || event.data._ak_ext !== 'authentik-platform-sso') return;
                forward(event.data.challenge);
              });
              const stage = document.querySelector('ak-stage-endpoint-agent');
              if (stage && stage.challenge) forward(stage.challenge.challenge);
            })();
        """

        fun appIntent(context: Context, url: String, mode: DeviceMode, title: String? = null): Intent =
            Intent(context, PortalBrowserActivity::class.java)
                .putExtra(EXTRA_URL, url)
                .putExtra(EXTRA_DEVICE_MODE, mode.name)
                .putExtra(EXTRA_TITLE, title ?: context.getString(R.string.browser_web_application))

        fun authorizationIntent(context: Context, url: String, mode: DeviceMode): Intent =
            Intent(context, PortalBrowserActivity::class.java)
                .putExtra(EXTRA_URL, url)
                .putExtra(EXTRA_TITLE, context.getString(R.string.browser_sign_in_title))
                .putExtra(EXTRA_REDIRECT_URI, BuildConfig.OIDC_REDIRECT_URI)
                .putExtra(EXTRA_DEVICE_MODE, mode.name)
                .putExtra(EXTRA_CLEAR_BEFORE_LOAD, mode == DeviceMode.SHARED)

        fun authorizationResponse(intent: Intent?): Uri? {
            val response = intent?.data?.toString()
                ?: intent?.getStringExtra(EXTRA_AUTHORIZATION_RESPONSE)
                ?: return null
            return runCatching { Uri.parse(response) }.getOrNull()
        }

        fun selfEnrollmentIntent(context: Context): Intent =
            Intent(context, PortalBrowserActivity::class.java)
                .putExtra(EXTRA_URL, BuildConfig.SELF_ENROLLMENT_URL)
                .putExtra(EXTRA_DEVICE_MODE, DeviceMode.PERSONAL.name)
                .putExtra(EXTRA_TITLE, context.getString(R.string.self_enrollment_browser_title))
                .putExtra(EXTRA_CLEAR_BEFORE_LOAD, true)
                .putExtra(EXTRA_SELF_ENROLLMENT, true)

        fun selfEnrollmentResponse(intent: Intent?): Uri? = intent?.data?.let { uri ->
            EnrollmentQrParser.parse(uri.toString())
                ?.takeIf { it.tokenUuid != null && it.mode == DeviceMode.PERSONAL }
                ?.let { uri }
        }

        fun sessionExpired(intent: Intent?): Boolean =
            intent?.getBooleanExtra(EXTRA_SESSION_EXPIRED, false) == true

        fun deviceBlocked(intent: Intent?): Boolean =
            intent?.getBooleanExtra(EXTRA_DEVICE_BLOCKED, false) == true

        fun logoutIntent(context: Context, url: String, mode: DeviceMode): Intent =
            Intent(context, PortalBrowserActivity::class.java)
                .putExtra(EXTRA_URL, url)
                .putExtra(EXTRA_DEVICE_MODE, mode.name)
                .putExtra(EXTRA_TITLE, context.getString(R.string.browser_sign_out_title))
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
