package de.missionleben.portal

import android.Manifest
import android.app.LocaleManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.LocaleList
import android.util.Log
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import de.missionleben.portal.model.DeviceMode
import de.missionleben.portal.model.VaultRequest
import de.missionleben.portal.push.NotificationPresenter
import de.missionleben.portal.push.PushCommand
import de.missionleben.portal.push.PushEventDispatcher
import de.missionleben.portal.push.PushManager
import de.missionleben.portal.push.PushRegistrationStore
import de.missionleben.portal.ui.MissionLebenApp
import de.missionleben.portal.ui.MissionLebenTheme
import de.missionleben.portal.update.UpdateInstaller
import de.missionleben.portal.update.UpdateStatus
import de.missionleben.portal.web.PortalBrowserActivity

private const val AUTH_LOG_TAG = "MissionLebenAuth"

class MainActivity : FragmentActivity() {
    private val viewModel: MainViewModel by viewModels()

    private val pushRegistrationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                PushEventDispatcher.ACTION_REGISTRATION_CHANGED -> viewModel.syncPushRegistration()
                PushEventDispatcher.ACTION_LOGIN_APPROVAL_CHANGED -> {
                    handleLoginApprovalWake(intent)
                }
                PushEventDispatcher.ACTION_SECURITY_STATE_CHANGED -> {
                    viewModel.acceptBackgroundDeviceStatus(
                        intent.getStringExtra(PushEventDispatcher.EXTRA_ENROLLMENT_STATE),
                    )
                }
                PushEventDispatcher.ACTION_UNREAD_CHANGED -> viewModel.refreshUnreadNotificationBadges()
            }
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) viewModel.syncPushRegistration()
    }

    private val updatePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        if (UpdateInstaller.canRequestInstalls(this)) {
            installVerifiedUpdate()
        } else {
            viewModel.updateInstallPermissionDenied()
        }
    }

    private val authorizationLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (PortalBrowserActivity.sharedSessionEnded(result.data)) {
            viewModel.endSharedSessionAfterScreenOff()
            return@registerForActivityResult
        }
        val redirectUri = if (result.resultCode == RESULT_OK) {
            PortalBrowserActivity.authorizationResponse(result.data)
        } else {
            null
        }
        Log.i(
            AUTH_LOG_TAG,
            "Authorization browser result: resultOk=${result.resultCode == RESULT_OK}, " +
                "hasResponse=${redirectUri != null}",
        )
        viewModel.completeAuthorization(redirectUri)
    }

    private val appBrowserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            when {
                PortalBrowserActivity.sharedSessionEnded(result.data) -> {
                    viewModel.endSharedSessionAfterScreenOff()
                }
                PortalBrowserActivity.deviceBlocked(result.data) -> {
                    viewModel.acceptBackgroundDeviceStatus(de.missionleben.portal.model.EnrollmentState.BLOCKED.name)
                }
                PortalBrowserActivity.sessionExpired(result.data) -> viewModel.sessionExpired()
            }
        }
    }

    private val selfEnrollmentLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val enrollment = PortalBrowserActivity.selfEnrollmentResponse(result.data) ?: return@registerForActivityResult
        viewModel.enrollDeviceFromQr(enrollment.toString()) {
            viewModel.createLoginUrl { url ->
                authorizationLauncher.launch(
                    PortalBrowserActivity.authorizationIntent(this, url, DeviceMode.PERSONAL),
                )
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PushManager.initialize(this)
        viewModel.acceptEnrollmentLink(intent?.data)
        viewModel.acceptPushAction(intent?.getStringExtra(PushEventDispatcher.EXTRA_PUSH_ACTION))
        handleLoginApprovalWake(intent)

        setContent {
            MissionLebenTheme {
                val state by viewModel.uiState.collectAsState()
                LaunchedEffect(state.vaultRequest) {
                    if (state.vaultRequest != VaultRequest.NONE) handleVaultRequest(state.vaultRequest)
                }
                LaunchedEffect(state.signedIn, state.mode) {
                    requestNotificationPermissionAfterLogin(state.signedIn)
                }
                LaunchedEffect(state.requestedUrl) {
                    state.requestedUrl?.let {
                        viewModel.consumeRequestedUrl()
                        openUrl(it)
                    }
                }
                LaunchedEffect(state.clearWebDataRequested) {
                    if (state.clearWebDataRequested) {
                        PortalBrowserActivity.clearLocalWebData(this@MainActivity) {
                            viewModel.consumeWebDataClearRequest()
                        }
                    }
                }
                LaunchedEffect(state.updateStatus) {
                    if (state.updateStatus == UpdateStatus.READY) installVerifiedUpdate()
                }
                MissionLebenApp(
                    state = state,
                    onStartLogin = {
                        state.mode?.let { mode ->
                            viewModel.createLoginUrl { url ->
                                authorizationLauncher.launch(
                                    PortalBrowserActivity.authorizationIntent(this@MainActivity, url, mode),
                                )
                            }
                        }
                    },
                    onRetryQuickUnlock = viewModel::retryQuickUnlock,
                    onOpenUrl = viewModel::openApplication,
                    onOpenPublicUrl = ::openUrl,
                    onReloadApplications = viewModel::loadApplications,
                    onMarkAnnouncementRead = viewModel::markAnnouncementRead,
                    onSelfEnrollment = {
                        selfEnrollmentLauncher.launch(
                            PortalBrowserActivity.selfEnrollmentIntent(this@MainActivity),
                        )
                    },
                    onRefreshDeviceStatus = viewModel::refreshDeviceStatus,
                    onEnableQuickUnlock = viewModel::retryQuickUnlockSetup,
                    onOpenTalk = viewModel::openTalkOn,
                    onNotificationPrivacyChange = viewModel::setNotificationPrivacy,
                    onCalendarReminderChange = viewModel::setCalendarReminderMinutes,
                    onCommunicationNotificationsChange = viewModel::setCommunicationNotificationsEnabled,
                    onQuietHoursChange = viewModel::setQuietHoursEnabled,
                    onQuietStartChange = viewModel::setQuietStartMinutes,
                    onQuietEndChange = viewModel::setQuietEndMinutes,
                    onLogout = { viewModel.logout(::openLogout) },
                    onResetProfile = {
                        PortalBrowserActivity.clearLocalWebData(this@MainActivity) {
                            viewModel.resetProfile()
                        }
                    },
                    onDismissMessage = viewModel::clearMessage,
                    onCheckForUpdates = { viewModel.checkForUpdates(force = true) },
                    onApproveLogin = { viewModel.decideLoginApproval(true) },
                    onDenyLogin = { viewModel.decideLoginApproval(false) },
                    onLoginApprovalExpired = viewModel::expireLoginApproval,
                    onInstallUpdate = viewModel::downloadUpdate,
                    onDismissUpdate = viewModel::dismissUpdate,
                    currentLanguageTag = currentLanguageTag(),
                    onLanguageChange = ::setAppLanguage,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        viewModel.acceptEnrollmentLink(intent.data)
        viewModel.acceptPushAction(intent.getStringExtra(PushEventDispatcher.EXTRA_PUSH_ACTION))
        handleLoginApprovalWake(intent)
    }

    override fun onResume() {
        super.onResume()
        viewModel.consumeSharedSessionScreenOffState()
    }

    override fun onStart() {
        super.onStart()
        viewModel.checkForUpdates()
        viewModel.refreshDeviceStatus()
        viewModel.refreshAnnouncements()
        viewModel.refreshUnreadNotificationBadges()
        viewModel.startLoginApprovalPolling()
        ContextCompat.registerReceiver(
            this,
            pushRegistrationReceiver,
            IntentFilter().apply {
                addAction(PushEventDispatcher.ACTION_REGISTRATION_CHANGED)
                addAction(PushEventDispatcher.ACTION_LOGIN_APPROVAL_CHANGED)
                addAction(PushEventDispatcher.ACTION_SECURITY_STATE_CHANGED)
                addAction(PushEventDispatcher.ACTION_UNREAD_CHANGED)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        MissionLebenApplication.setPortalVisible(true)
    }

    override fun onStop() {
        MissionLebenApplication.setPortalVisible(false)
        viewModel.stopLoginApprovalPolling()
        unregisterReceiver(pushRegistrationReceiver)
        super.onStop()
    }

    private fun handleLoginApprovalWake(intent: Intent?) {
        val requestId = intent
            ?.getStringExtra(PushEventDispatcher.EXTRA_LOGIN_APPROVAL_REQUEST_ID)
            ?.takeIf(PushCommand::validEventId)
            ?: return
        NotificationPresenter.cancelLoginApproval(this, requestId)
        intent.removeExtra(PushEventDispatcher.EXTRA_LOGIN_APPROVAL_REQUEST_ID)
        viewModel.refreshLoginApproval()
    }

    private fun requestNotificationPermissionAfterLogin(signedIn: Boolean) {
        if (!signedIn || !PushManager.configured) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            viewModel.syncPushRegistration()
            return
        }
        val store = PushRegistrationStore(this)
        if (store.permissionWasRequested) return
        store.permissionWasRequested = true
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun openUrl(url: String) {
        val mode = viewModel.uiState.value.mode ?: return
        appBrowserLauncher.launch(PortalBrowserActivity.appIntent(this, url, mode))
    }

    private fun openLogout(url: String) {
        val mode = viewModel.uiState.value.mode ?: return
        startActivity(PortalBrowserActivity.logoutIntent(this, url, mode))
    }

    private fun installVerifiedUpdate() {
        val apk = viewModel.readyUpdateFile() ?: return
        if (!UpdateInstaller.canRequestInstalls(this)) {
            updatePermissionLauncher.launch(UpdateInstaller.permissionIntent(this))
            return
        }
        runCatching { startActivity(UpdateInstaller.installIntent(this, apk)) }
            .onSuccess { viewModel.updateInstallStarted() }
            .onFailure { viewModel.updateInstallFailed() }
    }

    private fun handleVaultRequest(request: VaultRequest) {
        viewModel.consumeVaultRequest()
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
        if (BiometricManager.from(this).canAuthenticate(authenticators) != BiometricManager.BIOMETRIC_SUCCESS) {
            viewModel.vaultFailed(getString(R.string.biometric_setup_required))
            return
        }

        val cipher = runCatching { viewModel.createVaultCipher(request) }.getOrElse {
            viewModel.vaultFailed(getString(R.string.secure_storage_unavailable, it.message.orEmpty()))
            return
        }
        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    val authorizedCipher = result.cryptoObject?.cipher
                    if (authorizedCipher == null) {
                        viewModel.vaultFailed(getString(R.string.secure_key_not_released))
                    } else {
                        viewModel.completeVaultRequest(request, authorizedCipher)
                    }
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    viewModel.vaultFailed(getString(R.string.quick_access_failed, errString))
                }
            },
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(if (request == VaultRequest.SEAL) getString(R.string.quick_access_enable_title) else getString(R.string.portal_open_title))
            .setSubtitle(getString(R.string.biometric_subtitle))
            .setAllowedAuthenticators(authenticators)
            .build()
        prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher))
    }

    private fun currentLanguageTag(): String? = getSystemService(LocaleManager::class.java)
        .applicationLocales
        .toLanguageTags()
        .ifBlank { null }

    private fun setAppLanguage(languageTag: String?) {
        getSystemService(LocaleManager::class.java).applicationLocales =
            languageTag?.let { LocaleList.forLanguageTags(it) } ?: LocaleList.getEmptyLocaleList()
    }
}
