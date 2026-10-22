package de.missionleben.portal.web

import android.Manifest
import android.content.pm.PackageManager
import android.app.DownloadManager
import android.content.Context
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.ConsoleMessage
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
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import de.missionleben.portal.BuildConfig
import de.missionleben.portal.MissionLebenApplication
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import de.missionleben.portal.ui.MissionLebenTheme
import de.missionleben.portal.ui.PortalHeader
import de.missionleben.portal.R
import de.missionleben.portal.auth.AuthorizationFailure
import de.missionleben.portal.data.AppPreferences
import de.missionleben.portal.device.DeviceServiceRepository
import de.missionleben.portal.device.EnrollmentQrParser
import de.missionleben.portal.model.DeviceMode
import de.missionleben.portal.model.EnrollmentState
import de.missionleben.portal.push.NotificationBadgeTarget
import de.missionleben.portal.push.NotificationNavigation
import de.missionleben.portal.push.PushEventDispatcher
import de.missionleben.portal.security.SharedSessionLifecyclePolicy
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class PortalBrowserActivity : FragmentActivity() {
    private lateinit var webView: WebView
    private lateinit var progress: ProgressBar
    private var browserTitle by mutableStateOf("")
    private lateinit var policy: WebNavigationPolicy
    private lateinit var fileChooser: PortalFileChooser
    private var logoutFinished = false
    private var endpointBridgeInstalled = false
    private var authorizationResultDelivered = false
    private var personalLoginContextCleared = false
    private var selfEnrollmentResultDelivered = false
    private var sessionExpiredResultDelivered = false
    private var sharedSessionResultDelivered = false
    private var talkChatNoticeShown = false
    private var voiceDocumentStartInstalled = false
    private var voiceCaptureGuardReady = false
    private var voiceDocumentGeneration = 0L
    private val talkVoicePermissionGate: TalkVoicePermissionGate by lazy {
        TalkVoicePermissionGate(
            page = { webViewUrl().orEmpty() },
            contextAllowed = { voiceDocumentStartInstalled && voiceCaptureGuardReady && deviceMode != null &&
                intent.getBooleanExtra(EXTRA_APP_CONTENT, false) && protectedFileActionAllowed() },
            foreground = { lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && hasWindowFocus() },
            hasAudioPermission = { ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED },
            requestAndroidPermission = { microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
            deniedByUser = { Toast.makeText(this, R.string.talk_microphone_denied, Toast.LENGTH_LONG).show() },
        )
    }
    private val microphonePermissionLauncher: ActivityResultLauncher<String> = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        talkVoicePermissionGate.androidPermissionResult(granted)
        window.decorView.post { resumeTalkVoiceCapture(); talkVoicePermissionGate.resume() }
    }
    private var pendingInitialTargetUrl: String? = null
    private var initialHistoryUrl: String? = null
    private var pendingContactEmail: String? = null
    private var contactComposeAttempts = 0
    private var contactComposeRunning = false
    private val deviceService by lazy { DeviceServiceRepository(applicationContext) }
    private val sessionCookies by lazy { WebSessionCookies.forContext(applicationContext) }
    private val talkChatScript by lazy { TalkChatPolicy.script(AppPreferences(this).talkStartAtLastChat) }
    private val helpdeskLayoutScript by lazy {
        HelpdeskContainerLayout.script(getString(R.string.ticket_upload_or_photo))
    }
    private val sessionPolicy by lazy {
        WebSessionPolicy(
            BuildConfig.AUTHENTIK_BASE_URL,
            BuildConfig.AUTHENTIK_AUTHENTICATION_FLOW_SLUGS,
        )
    }
    private val authentikOrigin by lazy {
        Uri.parse(BuildConfig.AUTHENTIK_BASE_URL).let { "${it.scheme}://${it.authority}" }
    }
    private val passwordVisibilityScript by lazy {
        PasswordVisibilityPolicy.script(
            BuildConfig.AUTHENTIK_BASE_URL,
            getString(R.string.password_show),
            getString(R.string.password_hide),
            getString(R.string.password_step_hint),
        )
    }
    private val loginIdentifierScript by lazy {
        LoginIdentifierPolicy.script(BuildConfig.AUTHENTIK_BASE_URL,
            getString(R.string.login_identifier_hint), getString(R.string.login_identifier_invalid),
            getString(R.string.login_identifier_use), getString(R.string.login_identifier_denied_hint),
            getString(R.string.login_identifier_denied_summary), getString(R.string.login_identifier_technical))
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

    private val downloadCompleteReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
            // The broadcast is only a hint. Status and URI come from our owned DownloadManager ID.
            if (id in PersonalDownloads.pending(this@PortalBrowserActivity)) {
                openCompletedPersonalDownload()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)

        if (closePersonalContentAfterLock()) return

        val startUrl = intent.getStringExtra(EXTRA_URL).orEmpty()
        val redirectUri = intent.getStringExtra(EXTRA_REDIRECT_URI).orEmpty()
        policy = WebNavigationPolicy(BuildConfig.WEB_ALLOWED_HOST_SUFFIXES, redirectUri)
        if (!policy.isTrustedWebUrl(startUrl)) {
            Toast.makeText(this, R.string.browser_address_blocked, Toast.LENGTH_LONG).show()
            authorizationFailed(AuthorizationFailure.INVALID_RESPONSE)
            finish()
            return
        }
        if (WebViewCompat.getCurrentWebViewPackage(this) == null) {
            Toast.makeText(this, R.string.browser_webview_missing, Toast.LENGTH_LONG).show()
            authorizationFailed(AuthorizationFailure.BROWSER_UNAVAILABLE)
            finish()
            return
        }

        val initialTitle = if (TalkChatPolicy.isTalkPage(startUrl)) {
            getString(R.string.talk_chat_title)
        } else {
            intent.getStringExtra(EXTRA_TITLE).orEmpty()
        }
        buildLayout(initialTitle)
        fileChooser = PortalFileChooser(this,
            currentPage = { webView.url.orEmpty() },
            mayReturnFiles = { protectedFileActionAllowed() },
            mayUseTicketPhoto = { HelpdeskContainerLayout.isHelpdeskPage(webView.url.orEmpty()) &&
                intent.getBooleanExtra(EXTRA_APP_CONTENT, false) && protectedFileActionAllowed() },
        )
        configureWebView()
        sessionCookies.record(WebSessionDiagnostics.Event.BROWSER_OPEN, startUrl)
        installBackNavigation()

        if (savedInstanceState == null) pendingContactEmail = intent.getStringExtra(EXTRA_CONTACT_EMAIL)

        personalLoginContextCleared = savedInstanceState?.getBoolean(EXTRA_FRESH_CONTEXT_CONFIRMED, false) == true
        if (requiresFreshPersonalContext() && !personalLoginContextCleared) {
            webView.visibility = View.INVISIBLE
            clearLoginContext(this) { cleared ->
                if (isFinishing || isDestroyed) return@clearLoginContext
                if (!cleared || personalAuthorizationLocked()) {
                    Toast.makeText(this, R.string.auth_context_clear_failed, Toast.LENGTH_LONG).show()
                    authorizationFailed(if (!cleared) AuthorizationFailure.CONTEXT_RESET_FAILED
                        else AuthorizationFailure.LOCAL_LOCK_REQUIRED)
                    finish()
                } else {
                    personalLoginContextCleared = true
                    webView.visibility = View.VISIBLE
                    loadInitialUrl(startUrl)
                }
            }
        } else if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState)
        } else if (intent.getBooleanExtra(EXTRA_CLEAR_BEFORE_LOAD, false)) {
            clearLocalWebData(this) { loadInitialUrl(startUrl) }
        } else {
            loadInitialUrl(startUrl)
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
        ContextCompat.registerReceiver(
            this,
            downloadCompleteReceiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_EXPORTED,
        )
    }

    override fun onResume() {
        super.onResume()
        if (personalAuthorizationLocked()) {
            if (::webView.isInitialized) {
                webView.visibility = View.INVISIBLE
                webView.stopLoading()
            }
            authorizationFailed(AuthorizationFailure.LOCAL_LOCK_REQUIRED)
            finish()
            return
        }
        if (closePersonalContentAfterLock()) return
        val screenTurnedOff = AppPreferences(this).consumeSharedSessionScreenTurnedOff()
        if (
            SharedSessionLifecyclePolicy.shouldInvalidate(
                mode = deviceMode,
                screenTurnedOff = screenTurnedOff,
            ) &&
            !sharedSessionResultDelivered
        ) {
            sharedSessionResultDelivered = true
            if (::webView.isInitialized) {
                webView.stopLoading()
                webView.visibility = View.INVISIBLE
            }
            clearLocalWebData(this) {
                setResult(RESULT_OK, Intent().putExtra(EXTRA_SHARED_SESSION_ENDED, true))
                finish()
            }
        }
        // Also catch a completion delivered while this activity was stopped or recreated.
        window.decorView.post { openCompletedPersonalDownload() }
        window.decorView.post { resumeTalkVoiceCapture(); talkVoicePermissionGate.resume() }
    }

    private fun requiresFreshPersonalContext(): Boolean =
        deviceMode == DeviceMode.PERSONAL && intent.getBooleanExtra(EXTRA_FRESH_PERSONAL_CONTEXT, false)

    private fun protectedFileActionAllowed(): Boolean {
        if (isFinishing || isDestroyed || logoutFinished || sessionExpiredResultDelivered || sharedSessionResultDelivered) return false
        if (!::webView.isInitialized || !policy.isTrustedWebUrl(webView.url.orEmpty())) return false
        if (!intent.getBooleanExtra(EXTRA_APP_CONTENT, false)) return true
        val preferences = AppPreferences(this)
        return preferences.enrollmentState == EnrollmentState.TRUSTED && !preferences.reauthenticationRequired &&
            !(deviceMode == DeviceMode.PERSONAL &&
                (application as MissionLebenApplication).personalSessionLockTracker.isLockRequired()) &&
            !SharedSessionLifecyclePolicy.shouldInvalidate(deviceMode, preferences.hasSharedSessionScreenTurnedOff())
    }

    private fun personalAuthorizationLocked(): Boolean = requiresFreshPersonalContext() &&
        (application as MissionLebenApplication).personalSessionLockTracker.isLockRequired()

    private fun closePersonalContentAfterLock(): Boolean {
        if (deviceMode != DeviceMode.PERSONAL || !intent.getBooleanExtra(EXTRA_APP_CONTENT, false)) return false
        if (!(application as MissionLebenApplication).personalSessionLockTracker.isLockRequired()) return false
        if (::webView.isInitialized) {
            webView.visibility = View.INVISIBLE
            webView.stopLoading()
        }
        finish()
        return true
    }

    override fun onPause() {
        val permissionDialog = talkVoicePermissionGate.androidPromptOpen
        talkVoicePermissionGate.pause()
        if (!permissionDialog) stopTalkVoiceCapture()
        if (::webView.isInitialized && isAuthentikOrigin(webView.url.orEmpty())) {
            webView.evaluateJavascript(PasswordVisibilityPolicy.HIDE_SCRIPT, null)
        }
        persistPersonalCookies(WebSessionDiagnostics.Event.PERSONAL_PAUSE, webViewUrl())
        super.onPause()
    }

    private fun webViewUrl(): String? = if (::webView.isInitialized) webView.url else null

    private fun persistPersonalCookies(event: WebSessionDiagnostics.Event, url: String?) {
        if (!::webView.isInitialized || logoutFinished || sharedSessionResultDelivered ||
            intent.getBooleanExtra(EXTRA_LOGOUT, false) ||
            intent.getBooleanExtra(EXTRA_SELF_ENROLLMENT, false)
        ) return
        // This stores existing WebView cookies only; biometric lock never erases them.
        sessionCookies.persistPersonal(deviceMode, event, url)
    }

    private fun authorizationFailed(failure: AuthorizationFailure) {
        setResult(RESULT_CANCELED, Intent().putExtra(EXTRA_AUTHORIZATION_FAILURE, failure.name))
    }

    override fun onStop() {
        talkVoicePermissionGate.cancel()
        stopTalkVoiceCapture()
        unregisterReceiver(securityStateReceiver)
        unregisterReceiver(downloadCompleteReceiver)
        super.onStop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            openCompletedPersonalDownload()
            resumeTalkVoiceCapture()
            talkVoicePermissionGate.resume()
        } else if (!talkVoicePermissionGate.androidPromptOpen) {
            talkVoicePermissionGate.pause()
            stopTalkVoiceCapture()
        }
    }

    private fun stopTalkVoiceCapture() {
        if (::webView.isInitialized && TalkVoicePolicy.isNextcloudPage(webView.url.orEmpty())) {
            webView.evaluateJavascript(TalkVoicePolicy.STOP_CAPTURE_SCRIPT, null)
        }
    }

    private fun resumeTalkVoiceCapture() {
        if (voiceCaptureGuardReady && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
            hasWindowFocus() && intent.getBooleanExtra(EXTRA_APP_CONTENT, false) && protectedFileActionAllowed()
        ) {
            webView.evaluateJavascript(TalkVoicePolicy.RESUME_CAPTURE_SCRIPT, null)
        }
    }

    private fun canExportPersonalDownload(): Boolean {
        val preferences = AppPreferences(this)
        return PersonalDownloadPolicy.allowsExport(
            browserMode = deviceMode,
            registeredMode = preferences.deviceMode,
            profile = preferences.enrollmentProfile,
            state = preferences.enrollmentState,
            applicationContent = intent.getBooleanExtra(EXTRA_APP_CONTENT, false),
            reauthenticationRequired = preferences.reauthenticationRequired,
            locked = (application as MissionLebenApplication).personalSessionLockTracker.isLockRequired(),
        ) && !logoutFinished && !sessionExpiredResultDelivered &&
            !sharedSessionResultDelivered &&
            !intent.getBooleanExtra(EXTRA_LOGOUT, false)
    }

    private fun openCompletedPersonalDownload() {
        if (isFinishing || isDestroyed || !hasWindowFocus() ||
            !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) || !canExportPersonalDownload()
        ) return
        for (id in PersonalDownloads.pending(this).sorted()) {
            when (PersonalDownloads.status(this, id)) {
                DownloadManager.STATUS_SUCCESSFUL -> {
                    val autoOpen = AppPreferences(this).downloadsAutoOpenEnabled
                    val open = PersonalDownloads.takeCompletedViewIntent(this, id, autoOpen)
                    if (!autoOpen) continue
                    if (open == null) {
                        Toast.makeText(this, R.string.browser_download_open_failed, Toast.LENGTH_LONG).show()
                        continue
                    }
                    try {
                        startActivity(open)
                    } catch (_: ActivityNotFoundException) {
                        Toast.makeText(this, R.string.browser_download_no_viewer, Toast.LENGTH_LONG).show()
                    } catch (_: SecurityException) {
                        Toast.makeText(this, R.string.browser_download_open_failed, Toast.LENGTH_LONG).show()
                    }
                    // Open one document at a time; remaining completed downloads are in Downloads.
                    return
                }
                DownloadManager.STATUS_FAILED -> {
                    PersonalDownloads.forget(this, id)
                    Toast.makeText(this, R.string.browser_download_transfer_failed, Toast.LENGTH_LONG).show()
                }
            }
        }
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
        browserTitle = initialTitle.ifBlank { getString(R.string.browser_protected_area) }
        val toolbar = ComposeView(this).apply {
            setContent {
                MissionLebenTheme {
                    Box(Modifier.padding(horizontal = 16.dp)) {
                        PortalHeader(browserTitle, onBack = ::finishApplicationBrowserFromBack,
                            onReportProblem = intent.getStringExtra(EXTRA_REPORT_PROBLEM_APP)?.takeIf { it.isNotBlank() }?.let {
                                { reportProblem(it) }
                            })
                    }
                }
            }
        }

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
        installPasswordVisibility()
        installLoginIdentifierHelp()
        installTalkChatGuard()
        installAnnouncementCenterGuard()
        installAppHelpLayout()
        installDocumentsMobileLayout()
        installHelpdeskContainerLayout()
    }

    private fun installHelpdeskContainerLayout() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return
        WebViewCompat.addDocumentStartJavaScript(webView, helpdeskLayoutScript,
            HelpdeskContainerLayout.ORIGINS)
    }

    private fun installAppHelpLayout() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return
        WebViewCompat.addDocumentStartJavaScript(webView, AppHelpLayout.SCRIPT, setOf(AppHelpLayout.ORIGIN))
    }

    private fun installDocumentsMobileLayout() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return
        WebViewCompat.addDocumentStartJavaScript(webView, DocumentsMobileLayout.SCRIPT,
            setOf(DocumentsMobileLayout.ORIGIN))
    }

    private fun installPasswordVisibility() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return
        val script = passwordVisibilityScript ?: return
        WebViewCompat.addDocumentStartJavaScript(webView, script, setOf(authentikOrigin))
    }

    private fun installLoginIdentifierHelp() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return
        val script = loginIdentifierScript ?: return
        WebViewCompat.addDocumentStartJavaScript(webView, script, setOf(authentikOrigin))
    }

    private fun installTalkChatGuard() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return
        WebViewCompat.addDocumentStartJavaScript(
            webView,
            talkChatScript,
            setOf(TalkChatPolicy.NEXTCLOUD_ORIGIN),
        )
        voiceDocumentStartInstalled = runCatching {
            WebViewCompat.addDocumentStartJavaScript(webView, TalkVoicePolicy.CAPTURE_GUARD_SCRIPT,
                setOf(TalkChatPolicy.NEXTCLOUD_ORIGIN))
        }.isSuccess
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
                if (NotificationNavigation.isZimbraMailOverviewUrl(
                        webView.url.orEmpty(),
                        BuildConfig.ZIMBRA_WEB_BASE_URL,
                    ) || NotificationNavigation.isZimbraCalendarOverviewUrl(
                        webView.url.orEmpty(), BuildConfig.ZIMBRA_WEB_BASE_URL,
                    )
                ) {
                    finishApplicationBrowserFromBack()
                } else if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    finishApplicationBrowserFromBack()
                }
            }
        })
    }

    private fun finishApplicationBrowserFromBack() {
        intent.getStringExtra(EXTRA_NOTIFICATION_BADGE_TARGET)
            ?.let(NotificationBadgeTarget::fromSerialized)
            ?.let { target ->
                setResult(
                    RESULT_OK,
                    Intent().putExtra(EXTRA_VISITED_NOTIFICATION_BADGE_TARGET, target.name),
                )
            }
        finish()
    }

    private fun loadInitialUrl(startUrl: String) {
        val historyUrl = intent.getStringExtra(EXTRA_INITIAL_HISTORY_URL)
            ?.takeIf(policy::isTrustedWebUrl)
            ?.takeIf { sameWebOrigin(it, startUrl) }
        if (historyUrl == null || historyUrl == startUrl) {
            webView.loadUrl(startUrl)
            return
        }
        initialHistoryUrl = historyUrl
        pendingInitialTargetUrl = startUrl
        webView.loadUrl(historyUrl)
    }

    private fun loadPendingInitialTarget(view: WebView, currentUrl: String) {
        val targetUrl = pendingInitialTargetUrl ?: return
        val historyUrl = initialHistoryUrl ?: return
        if (!sameWebOrigin(currentUrl, historyUrl)) return
        pendingInitialTargetUrl = null
        initialHistoryUrl = null
        view.post {
            if (!isFinishing && !isDestroyed) view.loadUrl(targetUrl)
        }
    }

    private fun openPendingContactEmail() {
        val recipient = pendingContactEmail ?: return
        if (contactComposeRunning || isFinishing || isDestroyed) return
        if (!sameWebOrigin(webView.url.orEmpty(), BuildConfig.ZIMBRA_WEB_BASE_URL)) return
        val script = ZimbraContactCompose.script(BuildConfig.ZIMBRA_WEB_BASE_URL, recipient) ?: return
        contactComposeRunning = true
        webView.evaluateJavascript(script) { result ->
            contactComposeRunning = false
            if (isFinishing || isDestroyed) return@evaluateJavascript
            if (result == "\"opened\"") {
                pendingContactEmail = null
                // No address, message content, cookies or credentials in diagnostics.
                Log.i(AUTH_LOG_TAG, "Contact composer accepted by Zimbra")
            } else if (++contactComposeAttempts < 40) {
                webView.postDelayed({ openPendingContactEmail() }, 500L)
            } else {
                pendingContactEmail = null
                Toast.makeText(this, R.string.contacts_email_error, Toast.LENGTH_LONG).show()
                Log.w(AUTH_LOG_TAG, "Contact composer unavailable")
            }
        }
    }

    private inner class BrowserClient : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
            handleNavigation(request.url.toString(), request.isForMainFrame)

        @Deprecated("Compatibility for old WebView providers")
        override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean = handleNavigation(url, true)

        override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
            voiceDocumentGeneration++
            talkVoicePermissionGate.cancel()
            stopTalkVoiceCapture()
            voiceCaptureGuardReady = false
            if (::fileChooser.isInitialized) fileChooser.pageChanged(url)
            if (interceptExpiredSession(url)) return
            super.onPageStarted(view, url, favicon)
        }

        override fun onPageFinished(view: WebView, url: String) {
            super.onPageFinished(view, url)
            if (interceptExpiredSession(url)) return
            if (policy.isTrustedWebUrl(url)) {
                persistPersonalCookies(WebSessionDiagnostics.Event.PAGE_FINISHED, url)
            }
            loadPendingInitialTarget(view, url)
            openPendingContactEmail()
            browserTitle = view.title?.takeIf { it.isNotBlank() } ?: browserTitle
            if (isAuthentikOrigin(url)) {
                view.evaluateJavascript(ENDPOINT_BRIDGE_SCRIPT, null)
                // Idempotent fallback for providers without document-start script support.
                passwordVisibilityScript?.let { view.evaluateJavascript(it, null) }
                loginIdentifierScript?.let { view.evaluateJavascript(it, null) }
            }
            if (TalkChatPolicy.isTalkPage(url)) {
                // Fallback for providers without document-start script support and defense in depth
                // after Nextcloud/Talk SPA route changes.
                view.evaluateJavascript(talkChatScript, null)
                val generation = voiceDocumentGeneration
                view.evaluateJavascript(TalkVoicePolicy.CAPTURE_GUARD_SCRIPT) { installed ->
                    // Ignore results from a replaced document, but permit Talk's in-document room routes.
                    if (generation != voiceDocumentGeneration) return@evaluateJavascript
                    voiceCaptureGuardReady = TalkVoicePolicy.isGuardReady(voiceDocumentStartInstalled, installed,
                        generation, voiceDocumentGeneration, url, view.url.orEmpty())
                    if (BuildConfig.VERSION_NAME.endsWith("-local")) Log.d("MissionLebenVoice",
                        "guard start=$voiceDocumentStartInstalled installed=${installed == "true"} sameDocument=${generation == voiceDocumentGeneration} talk=${TalkVoicePolicy.isTalkDocument(view.url.orEmpty())} ready=$voiceCaptureGuardReady")
                    resumeTalkVoiceCapture()
                }
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
            if (AppHelpLayout.isHelpPage(url)) {
                // Also support WebView providers without document-start script injection.
                view.evaluateJavascript(AppHelpLayout.SCRIPT, null)
            }
            if (DocumentsMobileLayout.isDocumentOrigin(url)) {
                view.evaluateJavascript(DocumentsMobileLayout.SCRIPT, null)
            }
            if (HelpdeskContainerLayout.isHelpdeskPage(url)) {
                view.evaluateJavascript(helpdeskLayoutScript, null)
            }
            if (intent.getBooleanExtra(EXTRA_LOGOUT, false) && !logoutFinished) {
                logoutFinished = true
                webView.visibility = View.INVISIBLE
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

    private fun sameWebOrigin(first: String, second: String): Boolean = runCatching {
        val firstUri = Uri.parse(first)
        val secondUri = Uri.parse(second)
        firstUri.scheme.equals("https", ignoreCase = true) &&
            secondUri.scheme.equals("https", ignoreCase = true) &&
            firstUri.host.equals(secondUri.host, ignoreCase = true) &&
            firstUri.port == secondUri.port
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
            if (requiresFreshPersonalContext() && (!personalLoginContextCleared || personalAuthorizationLocked())) {
                authorizationFailed(AuthorizationFailure.LOCAL_LOCK_REQUIRED)
                finish()
                return true
            }
            if (!authorizationResultDelivered) {
                authorizationResultDelivered = true
                val response = Uri.parse(url)
                if (response.getQueryParameter("error") == null && response.getQueryParameter("code") != null) {
                    // Called before finish/result delivery; this is not proof of native token exchange.
                    persistPersonalCookies(WebSessionDiagnostics.Event.AUTHORIZATION_RETURN, BuildConfig.AUTHENTIK_BASE_URL)
                }
                Log.i(
                    AUTH_LOG_TAG,
                    "Authorization redirect received: hasCode=${response.getQueryParameter("code") != null}, " +
                        "hasError=${response.getQueryParameter("error") != null}, " +
                        "hasState=${response.getQueryParameter("state") != null}",
                )
                setResult(
                    RESULT_OK,
                    Intent()
                        .setData(response)
                        .putExtra(EXTRA_FRESH_CONTEXT_CONFIRMED, personalLoginContextCleared)
                        .putExtra(EXTRA_AUTHORIZATION_RESPONSE, url),
                )
                finish()
            }
            return true
        }
        // A meeting link opened from Zimbra needs media-capable Talk, not the chat-only WebView.
        // Talk's own chat navigation stays inside this WebView.
        if (intent.getBooleanExtra(EXTRA_APP_CONTENT, false) &&
            sameWebOrigin(webView.url.orEmpty(), BuildConfig.ZIMBRA_WEB_BASE_URL) &&
            TalkMeetingLinkPolicy.parse(url) != null
        ) {
            val result = Intent().putExtra(EXTRA_TALK_MEETING_URL, url)
            intent.getStringExtra(EXTRA_NOTIFICATION_BADGE_TARGET)?.let { target ->
                result.putExtra(EXTRA_VISITED_NOTIFICATION_BADGE_TARGET, target)
            }
            setResult(RESULT_OK, result)
            finish()
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
            sessionCookies.record(WebSessionDiagnostics.Event.WEB_REAUTHENTICATION, url)
            sessionExpiredResultDelivered = true
            webView.stopLoading()
            setResult(RESULT_OK, Intent().putExtra(EXTRA_SESSION_EXPIRED, true))
            finish()
        }
        return true
    }

    private inner class BrowserChromeClient : WebChromeClient() {
        override fun onConsoleMessage(message: ConsoleMessage): Boolean {
            val code = message.message()
            if (code.matches(Regex("ML_TALK_VOICE:(installed|request|blocked-disabled|blocked-frame|blocked-hidden|blocked-page|blocked-constraints|native-denied|late-capture|stopped|resumed)"))) {
                if (BuildConfig.VERSION_NAME.endsWith("-local")) Log.d("MissionLebenVoice", code)
                return true
            }
            return super.onConsoleMessage(message)
        }

        override fun onProgressChanged(view: WebView, newProgress: Int) {
            progress.progress = newProgress
            progress.visibility = if (newProgress >= 100) View.GONE else View.VISIBLE
        }

        override fun onReceivedTitle(view: WebView, title: String?) {
            if (!title.isNullOrBlank()) browserTitle = title
        }

        override fun onPermissionRequest(request: PermissionRequest) {
            if (BuildConfig.VERSION_NAME.endsWith("-local")) Log.d("MissionLebenVoice",
                "permission start=$voiceDocumentStartInstalled ready=$voiceCaptureGuardReady mode=${deviceMode != null} content=${intent.getBooleanExtra(EXTRA_APP_CONTENT, false)} protected=${protectedFileActionAllowed()} resumed=${lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)} focus=${hasWindowFocus()} scoped=${TalkVoicePolicy.allows(webViewUrl().orEmpty(), request.origin.toString(), request.resources.toList())}")
            talkVoicePermissionGate.request(object : TalkVoiceRequest {
                override val identity: Any = request
                override val origin: String = request.origin.toString()
                override val resources: List<String> = request.resources.toList()
                override fun grantAudio() {
                    // Never grant request.resources: camera, MIDI and future resource types remain denied.
                    runCatching { request.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) }
                }
                override fun deny() { runCatching { request.deny() } }
            })
        }

        override fun onPermissionRequestCanceled(request: PermissionRequest) {
            talkVoicePermissionGate.canceled(request)
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
            return fileChooser.show(filePathCallback, fileChooserParams)
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
                .replace(Regex("[^\\p{L}\\p{N}._ -]"), "_")
                .take(120)
                .ifBlank { "Download" }
            val publicExport = canExportPersonalDownload()
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setMimeType(mimeType)
                addRequestHeader("User-Agent", userAgent)
                CookieManager.getInstance().getCookie(url)?.let { addRequestHeader("Cookie", it) }
                setTitle(filename)
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                if (publicExport) {
                    setDestinationInExternalPublicDir(
                        Environment.DIRECTORY_DOWNLOADS, PersonalDownloads.uniqueFilename(filename),
                    )
                } else {
                    setDestinationInExternalFilesDir(this@PortalBrowserActivity, Environment.DIRECTORY_DOWNLOADS, filename)
                }
            }
            val manager = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
            runCatching { manager.enqueue(request) }
                .onSuccess { downloadId ->
                    if (publicExport) PersonalDownloads.record(this@PortalBrowserActivity, downloadId)
                    else recordDownload(this@PortalBrowserActivity, downloadId)
                    Toast.makeText(
                        this@PortalBrowserActivity,
                        if (publicExport) R.string.browser_download_started else R.string.browser_download_saved,
                        Toast.LENGTH_SHORT,
                    ).show()
                }
                .onFailure { Toast.makeText(this@PortalBrowserActivity, R.string.browser_download_failed, Toast.LENGTH_LONG).show() }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(EXTRA_FRESH_CONTEXT_CONFIRMED, personalLoginContextCleared)
        if (::webView.isInitialized) webView.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        talkVoicePermissionGate.cancel()
        stopTalkVoiceCapture()
        if (::fileChooser.isInitialized) fileChooser.destroy()
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

    private fun reportProblem(slug: String) {
        if (!intent.getBooleanExtra(EXTRA_APP_CONTENT, false)) return
        setResult(RESULT_OK, Intent().putExtra(EXTRA_REPORT_PROBLEM_APP, slug).apply {
            intent.getStringExtra(EXTRA_NOTIFICATION_BADGE_TARGET)?.let {
                putExtra(EXTRA_VISITED_NOTIFICATION_BADGE_TARGET, it)
            }
        })
        finish()
    }

    companion object {
        private const val EXTRA_REPORT_PROBLEM_APP = "report_problem_app_slug"
        private const val EXTRA_URL = "url"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_REDIRECT_URI = "redirect_uri"
        private const val EXTRA_AUTHORIZATION_RESPONSE = "authorization_response"
        private const val EXTRA_AUTHORIZATION_FAILURE = "authorization_failure"
        private const val EXTRA_SESSION_EXPIRED = "session_expired"
        private const val EXTRA_DEVICE_BLOCKED = "device_blocked"
        private const val EXTRA_SHARED_SESSION_ENDED = "shared_session_ended"
        private const val EXTRA_DEVICE_MODE = "device_mode"
        private const val EXTRA_APP_CONTENT = "app_content"
        private const val EXTRA_CLEAR_BEFORE_LOAD = "clear_before_load"
        private const val EXTRA_FRESH_PERSONAL_CONTEXT = "fresh_personal_context"
        private const val EXTRA_FRESH_CONTEXT_CONFIRMED = "fresh_context_confirmed"
        private const val EXTRA_LOGOUT = "logout"
        private const val EXTRA_SELF_ENROLLMENT = "self_enrollment"
        private const val EXTRA_INITIAL_HISTORY_URL = "initial_history_url"
        private const val EXTRA_CONTACT_EMAIL = "contact_email"
        private const val EXTRA_NOTIFICATION_BADGE_TARGET = "notification_badge_target"
        private const val EXTRA_VISITED_NOTIFICATION_BADGE_TARGET = "visited_notification_badge_target"
        private const val EXTRA_TALK_MEETING_URL = "talk_meeting_url"
        private const val DOWNLOAD_PREFERENCES = "protected_web_downloads"
        private const val DOWNLOAD_IDS = "download_ids"
        private const val ENDPOINT_BRIDGE_NAME = "MissionLebenEndpoint"
        private const val AUTH_LOG_TAG = "MissionLebenAuth"
        private const val WEB_DATA_CLEAR_TIMEOUT_MILLIS = 2_000L
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

        fun appIntent(
            context: Context,
            url: String,
            mode: DeviceMode,
            title: String? = null,
            notificationBadgeTarget: NotificationBadgeTarget? = null,
            problemReportApplicationSlug: String? = null,
        ): Intent =
            Intent(context, PortalBrowserActivity::class.java)
                .putExtra(EXTRA_URL, url)
                .putExtra(EXTRA_DEVICE_MODE, mode.name)
                .putExtra(EXTRA_APP_CONTENT, true)
                .putExtra(EXTRA_TITLE, title ?: context.getString(R.string.browser_web_application))
                .putExtra(EXTRA_REPORT_PROBLEM_APP, problemReportApplicationSlug)
                .apply {
                    notificationBadgeTarget?.let {
                        putExtra(EXTRA_NOTIFICATION_BADGE_TARGET, it.name)
                    }
                    NotificationNavigation.zimbraMailOverviewUrl(url, BuildConfig.ZIMBRA_WEB_BASE_URL)?.let { inbox ->
                        val recipient = de.missionleben.portal.data.ContactActions.zimbraComposeRecipient(
                            url, BuildConfig.ZIMBRA_WEB_BASE_URL,
                        )
                        if (recipient != null) {
                            // Never send the contact address as a query string to the mail server.
                            putExtra(EXTRA_URL, inbox)
                            putExtra(EXTRA_CONTACT_EMAIL, recipient)
                        } else putExtra(EXTRA_INITIAL_HISTORY_URL, inbox)
                    }
                    NotificationNavigation.zimbraCalendarOverviewUrl(url, BuildConfig.ZIMBRA_WEB_BASE_URL)?.let { calendar ->
                        putExtra(EXTRA_INITIAL_HISTORY_URL, calendar)
                    }
                }

        fun authorizationIntent(context: Context, url: String, mode: DeviceMode): Intent =
            Intent(context, PortalBrowserActivity::class.java)
                .putExtra(EXTRA_URL, url)
                .putExtra(EXTRA_TITLE, context.getString(R.string.browser_sign_in_title))
                .putExtra(EXTRA_REDIRECT_URI, BuildConfig.OIDC_REDIRECT_URI)
                .putExtra(EXTRA_DEVICE_MODE, mode.name)
                .putExtra(EXTRA_CLEAR_BEFORE_LOAD, mode == DeviceMode.SHARED)
                .putExtra(EXTRA_FRESH_PERSONAL_CONTEXT, mode == DeviceMode.PERSONAL)

        fun freshContextConfirmed(intent: Intent?): Boolean =
            intent?.getBooleanExtra(EXTRA_FRESH_CONTEXT_CONFIRMED, false) == true

        fun authorizationFailure(intent: Intent?): AuthorizationFailure? =
            intent?.getStringExtra(EXTRA_AUTHORIZATION_FAILURE)?.let { value ->
                AuthorizationFailure.entries.firstOrNull { it.name == value }
                    ?: AuthorizationFailure.INVALID_RESPONSE
            }

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

        fun sharedSessionEnded(intent: Intent?): Boolean =
            intent?.getBooleanExtra(EXTRA_SHARED_SESSION_ENDED, false) == true

        fun visitedNotificationTarget(intent: Intent?): NotificationBadgeTarget? =
            NotificationBadgeTarget.fromSerialized(
                intent?.getStringExtra(EXTRA_VISITED_NOTIFICATION_BADGE_TARGET),
            )

        fun talkMeetingUrl(intent: Intent?): String? =
            intent?.getStringExtra(EXTRA_TALK_MEETING_URL)
                ?.takeIf { TalkMeetingLinkPolicy.parse(it) != null }

        fun problemReportApplication(intent: Intent?): String? =
            intent?.getStringExtra(EXTRA_REPORT_PROBLEM_APP)?.takeIf { it.isNotBlank() && it.length <= 200 }

        fun logoutIntent(context: Context, url: String, mode: DeviceMode): Intent =
            Intent(context, PortalBrowserActivity::class.java)
                .putExtra(EXTRA_URL, url)
                .putExtra(EXTRA_DEVICE_MODE, mode.name)
                .putExtra(EXTRA_TITLE, context.getString(R.string.browser_sign_out_title))
                .putExtra(EXTRA_LOGOUT, true)

        /** Authentication-only reset: no downloads, files, device registration or push data removed. */
        fun clearLoginContext(context: Context, onComplete: (Boolean) -> Unit) {
            val handler = Handler(Looper.getMainLooper())
            val cookies = CookieManager.getInstance()
            val diagnostics = WebSessionCookies.forContext(context)
            diagnostics.record(WebSessionDiagnostics.Event.FRESH_LOGIN_CLEAR)
            val gate = CookieClearanceGate { success ->
                if (!success) {
                    diagnostics.record(WebSessionDiagnostics.Event.CLEAR_FAILED)
                    onComplete(false)
                } else {
                    val storageCleared = runCatching {
                        diagnostics.record(WebSessionDiagnostics.Event.CLEAR_ACKNOWLEDGED)
                        check(diagnostics.persist())
                        WebStorage.getInstance().deleteAllData()
                        WebViewDatabase.getInstance(context).apply {
                            clearHttpAuthUsernamePassword()
                            clearFormData()
                        }
                        WebView(context).apply {
                            clearCache(true)
                            clearHistory()
                            clearFormData()
                            destroy()
                        }
                    }.isSuccess
                    onComplete(storageCleared)
                }
            }
            runCatching {
                cookies.removeAllCookies { gate.acknowledged(cookies.hasCookies()) }
            }.onFailure { gate.failed() }
            handler.postDelayed({ gate.failed() }, 10_000L)
        }

        fun clearLocalWebData(context: Context, onComplete: (() -> Unit)? = null) {
            val diagnostics = WebSessionCookies.forContext(context)
            diagnostics.record(WebSessionDiagnostics.Event.LOCAL_DATA_CLEAR)
            TicketPhotoStore.clearAll(context)
            val completed = AtomicBoolean(false)
            val completeClear: () -> Unit = {
                if (completed.compareAndSet(false, true)) {
                    // A flush failure must not prevent removal of the remaining local data.
                    diagnostics.persist()
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
                    PersonalDownloads.cancelPending(context)
                    context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                        ?.takeIf(File::exists)
                        ?.deleteRecursively()
                    onComplete?.invoke()
                }
            }
            CookieManager.getInstance().removeAllCookies {
                diagnostics.record(if (CookieManager.getInstance().hasCookies())
                    WebSessionDiagnostics.Event.CLEAR_FAILED else WebSessionDiagnostics.Event.CLEAR_ACKNOWLEDGED)
                completeClear()
            }
            Handler(Looper.getMainLooper()).postDelayed(
                {
                    if (!completed.get()) diagnostics.record(WebSessionDiagnostics.Event.CLEAR_FAILED)
                    completeClear()
                },
                WEB_DATA_CLEAR_TIMEOUT_MILLIS,
            )
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
