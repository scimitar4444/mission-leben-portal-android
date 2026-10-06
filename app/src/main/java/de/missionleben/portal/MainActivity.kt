package de.missionleben.portal

import android.Manifest
import android.app.AlertDialog
import android.app.LocaleManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Build
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import de.missionleben.portal.model.DeviceMode
import de.missionleben.portal.model.AppFeaturePolicy
import de.missionleben.portal.model.EnrollmentState
import de.missionleben.portal.calendar.LocalCalendarStore
import de.missionleben.portal.model.VaultRequest
import de.missionleben.portal.push.NotificationBadgeTarget
import de.missionleben.portal.push.NotificationPresenter
import de.missionleben.portal.push.PushCommand
import de.missionleben.portal.push.PushEventDispatcher
import de.missionleben.portal.push.PushManager
import de.missionleben.portal.push.PushReliabilitySettings
import de.missionleben.portal.push.PushRegistrationStore
import de.missionleben.portal.ui.MissionLebenApp
import de.missionleben.portal.ui.MissionLebenTheme
import de.missionleben.portal.ui.ReleaseNotesDialog
import de.missionleben.portal.ui.SetupAssistantDialog
import de.missionleben.portal.setup.SetupAssistantPolicy
import de.missionleben.portal.setup.SetupAssistantStore
import de.missionleben.portal.update.ReleaseNotesStore
import de.missionleben.portal.update.UpdateInstaller
import de.missionleben.portal.update.UpdateStatus
import de.missionleben.portal.web.PortalBrowserActivity
import de.missionleben.portal.web.TalkMeetingLinkPolicy
import de.missionleben.portal.web.TalkAppLaunchPolicy
import de.missionleben.portal.web.TalkAppTarget
import kotlinx.coroutines.launch

private const val AUTH_LOG_TAG = "MissionLebenAuth"

class MainActivity : FragmentActivity() {
    private val viewModel: MainViewModel by viewModels()
    private val pushReliability by lazy { PushReliabilitySettings(this) }
    private val releaseNotes by lazy { ReleaseNotesStore(this) }
    private val setupAssistant by lazy { SetupAssistantStore(this) }
    private val pushSettingsRevision = mutableIntStateOf(0)

    private val pushRegistrationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                PushEventDispatcher.ACTION_REGISTRATION_CHANGED -> {
                    viewModel.syncPushRegistration()
                    pushSettingsRevision.intValue++
                }
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
        pushSettingsRevision.intValue++
    }

    private val calendarPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        viewModel.setCalendarSyncEnabled(true)
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
        applyPersonalSessionLockIfNeeded()
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
        viewModel.completeAuthorization(redirectUri, PortalBrowserActivity.freshContextConfirmed(result.data))
    }

    private val appBrowserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            PortalBrowserActivity.visitedNotificationTarget(result.data)
                ?.let(viewModel::markApplicationVisited)
            PortalBrowserActivity.talkMeetingUrl(result.data)?.let { talkUrl ->
                routeTalkMeeting(talkUrl)
                return@registerForActivityResult
            }
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
            startLogin()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        PushManager.initialize(this)
        viewModel.acceptEnrollmentLink(intent?.data)
        viewModel.acceptPushAction(
            intent?.getStringExtra(PushEventDispatcher.EXTRA_PUSH_ACTION),
            intent?.getStringExtra(PushEventDispatcher.EXTRA_EVENT_ID),
        )
        handleLoginApprovalWake(intent)

        setContent {
            MissionLebenTheme {
                val state by viewModel.uiState.collectAsState()
                val pushStatus = remember(pushSettingsRevision.intValue, state.signedIn) {
                    pushReliability.status()
                }
                val notificationPermissionRequested = remember(pushSettingsRevision.intValue, state.signedIn) {
                    PushRegistrationStore(this@MainActivity).permissionWasRequested
                }
                val notesToShow = remember { mutableStateOf(releaseNotes.pendingNotes()) }
                val setupSteps = remember { mutableStateOf(emptyList<de.missionleben.portal.setup.SetupStep>()) }
                val setupScope = remember { mutableStateOf<String?>(null) }
                val setupPosition = remember { mutableIntStateOf(0) }
                val availableSetupSteps = SetupAssistantPolicy.available(
                    state.signedIn,
                    state.mode,
                    state.pushConfigured,
                    AppFeaturePolicy.from(state.applications).communication,
                    AppFeaturePolicy.from(state.applications).zimbra,
                    state.calendarAvailable,
                )
                LaunchedEffect(
                    state.signedIn, state.enrollmentState, state.applicationsLoading,
                    state.deviceId, state.user?.subject, notesToShow.value,
                    availableSetupSteps, state.calendarSyncEnabled, state.loginApprovalRequest,
                ) {
                    if (!state.signedIn || state.enrollmentState != EnrollmentState.TRUSTED) {
                        setupSteps.value = emptyList()
                        setupScope.value = null
                    } else if (setupSteps.value.isNotEmpty()) {
                        val stillAvailable = setupSteps.value.filter(availableSetupSteps::contains)
                        if (stillAvailable.size != setupSteps.value.size) {
                            setupSteps.value = stillAvailable
                            setupPosition.intValue = setupPosition.intValue.coerceAtMost(
                                (stillAvailable.size - 1).coerceAtLeast(0),
                            )
                        }
                    } else if (notesToShow.value.isEmpty() && !state.applicationsLoading &&
                        state.loginApprovalRequest == null && setupSteps.value.isEmpty()) {
                        val scope = setupAssistant.scope(state.mode, state.deviceId, state.user?.subject)
                        if (scope != null) {
                            val seen = setupAssistant.seen(
                                scope,
                                legacyPushHandled = notificationPermissionRequested || pushReliability.promptShown,
                                calendarAlreadyEnabled = state.calendarSyncEnabled,
                            )
                            val pending = SetupAssistantPolicy.pending(availableSetupSteps, seen)
                            if (pending.isNotEmpty()) {
                                setupScope.value = scope
                                setupPosition.intValue = 0
                                setupSteps.value = pending
                            }
                        }
                    }
                }
                LaunchedEffect(state.vaultRequest) {
                    if (state.vaultRequest != VaultRequest.NONE) handleVaultRequest(state.vaultRequest)
                }
                LaunchedEffect(state.requestedUrl) {
                    state.requestedUrl?.let {
                        val notificationBadgeTarget = state.requestedNotificationBadgeTarget
                        viewModel.consumeRequestedUrl()
                        openUrl(it, notificationBadgeTarget)
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
                    onStartLogin = { startLogin() },
                    onRetryQuickUnlock = viewModel::retryQuickUnlock,
                    onOpenUrl = viewModel::openApplication,
                    onOpenPublicUrl = ::openUrl,
                    onReloadApplications = viewModel::loadApplications,
                    onSearchContacts = viewModel::searchContacts,
                    onComposeContactEmail = viewModel::composeContactEmail,
                    onMarkAnnouncementRead = viewModel::markAnnouncementRead,
                    onSelfEnrollment = {
                        selfEnrollmentLauncher.launch(
                            PortalBrowserActivity.selfEnrollmentIntent(this@MainActivity),
                        )
                    },
                    onRefreshDeviceStatus = viewModel::refreshDeviceStatus,
                    onMoveToBackground = { moveTaskToBack(true) },
                    onEnableQuickUnlock = viewModel::retryQuickUnlockSetup,
                    onOpenTalk = viewModel::openTalkOn,
                    onNotificationPrivacyChange = viewModel::setNotificationPrivacy,
                    onCalendarReminderChange = viewModel::setCalendarReminderMinutes,
                    onCalendarSyncEnabledChange = ::setCalendarSyncEnabled,
                    onCalendarSyncDaysChange = viewModel::setCalendarSyncDays,
                    onDownloadsAutoOpenChange = viewModel::setDownloadsAutoOpenEnabled,
                    onTalkStartAtLastChatChange = viewModel::setTalkStartAtLastChat,
                    onCommunicationNotificationsChange = viewModel::setCommunicationNotificationsEnabled,
                    onQuietHoursChange = viewModel::setQuietHoursEnabled,
                    onQuietStartChange = viewModel::setQuietStartMinutes,
                    onQuietEndChange = viewModel::setQuietEndMinutes,
                    pushReliabilityStatus = pushStatus,
                    onOpenNotificationSettings = pushReliability::openNotificationSettings,
                    onRequestBatteryExemption = pushReliability::requestBatteryExemption,
                    onOpenManufacturerSettings = pushReliability::openManufacturerSettings,
                    onLogout = { viewModel.logout(::openLogout) },
                    onResetProfile = {
                        PortalBrowserActivity.clearLocalWebData(this@MainActivity) {
                            viewModel.resetProfile()
                        }
                    },
                    onDismissMessage = viewModel::clearMessage,
                    onCheckForUpdates = { viewModel.checkForUpdates(force = true) },
                    onShowReleaseNotes = { notesToShow.value = releaseNotes.currentVersionNotes() },
                    onOpenSetupAssistant = {
                        setupAssistant.scope(state.mode, state.deviceId, state.user?.subject)?.let { scope ->
                            setupScope.value = scope
                            setupPosition.intValue = 0
                            setupSteps.value = availableSetupSteps
                        }
                    },
                    onApproveLogin = { viewModel.decideLoginApproval(true) },
                    onDenyLogin = { viewModel.decideLoginApproval(false) },
                    onLoginApprovalExpired = viewModel::expireLoginApproval,
                    onInstallUpdate = viewModel::downloadUpdate,
                    onDismissUpdate = viewModel::dismissUpdate,
                    currentLanguageTag = currentLanguageTag(),
                    onLanguageChange = ::setAppLanguage,
                )
                if (notesToShow.value.isNotEmpty()) {
                    ReleaseNotesDialog(notesToShow.value) {
                        releaseNotes.markSeen()
                        notesToShow.value = emptyList()
                    }
                } else if (state.loginApprovalRequest == null && setupSteps.value.isNotEmpty() &&
                    setupPosition.intValue < setupSteps.value.size) {
                    SetupAssistantDialog(
                        steps = setupSteps.value,
                        position = setupPosition.intValue + 1,
                        state = state,
                        pushStatus = pushStatus,
                        onRequestNotifications = ::requestNotificationPermissionFromAssistant,
                        onRequestBatteryExemption = pushReliability::requestBatteryExemption,
                        onOpenManufacturerSettings = pushReliability::openManufacturerSettings,
                        onCalendarSyncEnabledChange = ::setCalendarSyncEnabled,
                        onCalendarSyncDaysChange = viewModel::setCalendarSyncDays,
                        onNotificationPrivacyChange = viewModel::setNotificationPrivacy,
                        onCalendarReminderChange = viewModel::setCalendarReminderMinutes,
                        onCommunicationNotificationsChange = viewModel::setCommunicationNotificationsEnabled,
                        onQuietHoursChange = viewModel::setQuietHoursEnabled,
                        onQuietStartChange = viewModel::setQuietStartMinutes,
                        onQuietEndChange = viewModel::setQuietEndMinutes,
                        onSelectStep = { selected -> setupPosition.intValue = selected - 1 },
                        onNext = {
                            if (setupPosition.intValue + 1 == setupSteps.value.size) {
                                setupScope.value?.let { setupAssistant.markSeen(it, setupSteps.value) }
                                setupSteps.value = emptyList()
                            } else {
                                setupScope.value?.let {
                                    setupAssistant.markSeen(it, listOf(setupSteps.value[setupPosition.intValue]))
                                }
                                setupPosition.intValue++
                            }
                        },
                        onLater = {
                            setupScope.value?.let {
                                setupAssistant.markSeen(it, setupSteps.value.drop(setupPosition.intValue))
                            }
                            setupSteps.value = emptyList()
                        },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        applyPersonalSessionLockIfNeeded()
        setIntent(intent)
        viewModel.acceptEnrollmentLink(intent.data)
        viewModel.acceptPushAction(
            intent.getStringExtra(PushEventDispatcher.EXTRA_PUSH_ACTION),
            intent.getStringExtra(PushEventDispatcher.EXTRA_EVENT_ID),
        )
        handleLoginApprovalWake(intent)
    }

    override fun onResume() {
        super.onResume()
        applyPersonalSessionLockIfNeeded()
        pushSettingsRevision.intValue++
        viewModel.consumeSharedSessionScreenOffState()
    }

    private fun applyPersonalSessionLockIfNeeded() {
        val tracker = (application as MissionLebenApplication).personalSessionLockTracker
        if (tracker.consumeLockRequired()) viewModel.lockPersonalSession()
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

    private fun requestNotificationPermissionFromAssistant() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            viewModel.syncPushRegistration()
            pushReliability.openNotificationSettings()
            return
        }
        val store = PushRegistrationStore(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !store.permissionWasRequested) {
            store.permissionWasRequested = true
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            pushReliability.openNotificationSettings()
        }
    }

    private fun setCalendarSyncEnabled(enabled: Boolean) {
        if (!enabled || LocalCalendarStore(this).hasPermission()) {
            viewModel.setCalendarSyncEnabled(enabled)
        } else {
            calendarPermissionLauncher.launch(
                arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR),
            )
        }
    }

    private fun openUrl(url: String) {
        openUrl(url, notificationBadgeTarget = null)
    }

    private fun routeTalkMeeting(url: String) {
        if (TalkMeetingLinkPolicy.parse(url) == null) return
        lifecycleScope.launch {
            val targets = viewModel.onlineTalkTargets()
            if (isFinishing || isDestroyed || viewModel.uiState.value.enrollmentState == EnrollmentState.BLOCKED) {
                return@launch
            }
            if (targets.isEmpty()) {
                openTalkMeetingOnPhone(url)
                return@launch
            }
            val choices = arrayOf(getString(R.string.talk_meeting_on_phone)) +
                targets.map { it.name }.toTypedArray()
            AlertDialog.Builder(this@MainActivity)
                .setTitle(R.string.talk_meeting_open_where)
                .setItems(choices) { _, index ->
                    if (index == 0) openTalkMeetingOnPhone(url)
                    else viewModel.openTalkOn(targets[index - 1].id, url)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun openTalkMeetingOnPhone(url: String) {
        val meeting = TalkMeetingLinkPolicy.parse(url) ?: return
        val installed = try {
            packageManager.getPackageInfo("com.nextcloud.talk2", 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        } catch (_: Exception) {
            showTalkMeetingOpenError()
            return
        }
        if (TalkAppLaunchPolicy.target(installed) == TalkAppTarget.CHROME) {
            openTalkMeetingInChrome(meeting.webUrl)
            return
        }
        val talk = Intent(Intent.ACTION_VIEW, Uri.parse(meeting.appUrl))
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .setPackage("com.nextcloud.talk2")
        runCatching { startActivity(talk) }
            .onFailure { showTalkMeetingOpenError() }
    }

    private fun showTalkMeetingOpenError() {
        AlertDialog.Builder(this)
            .setMessage(R.string.talk_meeting_native_failed)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun openTalkMeetingInChrome(url: String) {
        val chrome = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .setPackage("com.android.chrome")
        runCatching { startActivity(chrome) }
            .onFailure {
                runCatching {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))
                        .addCategory(Intent.CATEGORY_BROWSABLE))
                }
            }
    }

    private fun openUrl(
        url: String,
        notificationBadgeTarget: NotificationBadgeTarget?,
    ) {
        val mode = viewModel.uiState.value.mode ?: return
        appBrowserLauncher.launch(
            PortalBrowserActivity.appIntent(
                context = this,
                url = url,
                mode = mode,
                notificationBadgeTarget = notificationBadgeTarget,
            ),
        )
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

    private fun startLogin(persistSession: Boolean? = null) {
        val mode = viewModel.uiState.value.mode ?: return
        if (mode == DeviceMode.PERSONAL && persistSession == null && !hasLocalAuthenticator()) {
            AlertDialog.Builder(this)
                .setTitle(R.string.auth_session_only_title)
                .setMessage(R.string.auth_session_only_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.auth_session_only_continue) { _, _ -> startLogin(persistSession = false) }
                .show()
            return
        }
        viewModel.createLoginUrl(persistSession = persistSession != false) { url ->
            authorizationLauncher.launch(PortalBrowserActivity.authorizationIntent(this, url, mode))
        }
    }

    private fun hasLocalAuthenticator(): Boolean = BiometricManager.from(this).canAuthenticate(
        BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL,
    ) == BiometricManager.BIOMETRIC_SUCCESS

    private fun handleVaultRequest(request: VaultRequest) {
        viewModel.consumeVaultRequest()
        val requestEpoch = viewModel.currentSessionEpoch()
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
        if (!hasLocalAuthenticator()) {
            viewModel.vaultFailed(getString(R.string.biometric_setup_required), requestEpoch)
            startLogin()
            return
        }

        val cipher = runCatching { viewModel.createVaultCipher(request) }.getOrElse {
            viewModel.vaultFailed(getString(R.string.secure_storage_unavailable, it.message.orEmpty()), requestEpoch)
            return
        }
        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    val authorizedCipher = result.cryptoObject?.cipher
                    if (authorizedCipher == null) {
                        viewModel.vaultFailed(getString(R.string.secure_key_not_released), requestEpoch)
                    } else {
                        viewModel.completeVaultRequest(request, authorizedCipher, requestEpoch)
                    }
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    viewModel.vaultFailed(getString(R.string.quick_access_failed, errString), requestEpoch)
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
