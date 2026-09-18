package de.missionleben.portal

import android.Manifest
import android.app.Application
import android.app.job.JobScheduler
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.missionleben.portal.auth.AuthRepository
import de.missionleben.portal.data.AppPreferences
import de.missionleben.portal.data.PortalRepository
import de.missionleben.portal.device.DeviceServiceRepository
import de.missionleben.portal.device.EnrollmentQrParser
import de.missionleben.portal.model.DeviceMode
import de.missionleben.portal.model.EnrollmentState
import de.missionleben.portal.model.UiState
import de.missionleben.portal.model.VaultRequest
import de.missionleben.portal.push.PushAction
import de.missionleben.portal.push.PushManager
import de.missionleben.portal.push.NotificationPrivacy
import de.missionleben.portal.push.PushRegistrationStore
import de.missionleben.portal.security.DeviceIdentity
import de.missionleben.portal.security.SecureSessionVault
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.crypto.Cipher

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences = AppPreferences(application)
    private val vault = SecureSessionVault(application)
    private val identity = DeviceIdentity()
    private val authRepository = AuthRepository(application)
    private val portalRepository = PortalRepository()
    private val deviceService = DeviceServiceRepository(application)
    private val pushStore = PushRegistrationStore(application)

    private var serializedAuthState: String? = null
    private var dataEncryptionKey: ByteArray? = null
    private var pendingVaultState: String? = null
    private var pendingPushAction: PushAction? = null

    private val _uiState = MutableStateFlow(
        UiState(
            mode = preferences.deviceMode,
            enrollmentState = preferences.enrollmentState,
            deviceId = preferences.deviceId,
            deviceKeyId = identity.keyId(),
            deviceServiceConfigured = deviceService.endpointDevicesConfigured,
            communicationServiceConfigured = deviceService.communicationConfigured,
            pushConfigured = PushManager.configured,
            notificationPrivacy = effectiveNotificationPrivacy(preferences.deviceMode),
            quickUnlockEnabled = vault.hasSession(),
        ),
    )
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        val storedDeviceId = preferences.deviceId
        if (storedDeviceId != null && !deviceService.hasDeviceCredential(storedDeviceId)) {
            preferences.deviceId = null
            preferences.enrollmentState = EnrollmentState.NOT_ENROLLED
            vault.clear()
            clearNotifications()
            _uiState.update {
                it.copy(
                    deviceId = null,
                    enrollmentState = EnrollmentState.NOT_ENROLLED,
                    quickUnlockEnabled = false,
                    clearWebDataRequested = true,
                )
            }
        }
        if (preferences.deviceMode == DeviceMode.PERSONAL && vault.hasSession()) {
            _uiState.update { it.copy(vaultRequest = VaultRequest.UNLOCK) }
        }
    }

    fun selectMode(mode: DeviceMode) {
        if (preferences.deviceMode != mode) {
            serializedAuthState = null
            dataEncryptionKey = null
            vault.clear()
            clearNotifications()
        }
        preferences.deviceMode = mode
        _uiState.update {
            it.copy(
                mode = mode,
                signedIn = false,
                user = null,
                applications = emptyList(),
                quickUnlockEnabled = false,
                notificationPrivacy = effectiveNotificationPrivacy(mode),
                message = null,
            )
        }
    }

    fun resetProfile() {
        val oldState = serializedAuthState
        if (oldState != null) disconnectPushAndRevoke(oldState)
        serializedAuthState = null
        pendingVaultState = null
        dataEncryptionKey = null
        vault.clear()
        clearNotifications()
        deviceService.clearDeviceCredential()
        preferences.clearProfile()
        _uiState.value = UiState(
            deviceKeyId = identity.keyId(),
            deviceServiceConfigured = deviceService.endpointDevicesConfigured,
            communicationServiceConfigured = deviceService.communicationConfigured,
            pushConfigured = PushManager.configured,
            notificationPrivacy = NotificationPrivacy.MINIMAL,
        )
    }

    fun createLoginUrl(onSuccess: (String) -> Unit) {
        val mode = _uiState.value.mode ?: return
        _uiState.update { it.copy(busy = true, message = null) }
        authRepository.createAuthorizationUrl(
            mode = mode,
            onSuccess = {
                _uiState.update { state -> state.copy(busy = false) }
                onSuccess(it)
            },
            onError = { message -> _uiState.update { it.copy(busy = false, message = message) } },
        )
    }

    fun completeAuthorization(redirectUri: Uri?) {
        if (redirectUri == null) {
            _uiState.update { it.copy(message = string(R.string.message_auth_cancelled)) }
            return
        }
        _uiState.update { it.copy(busy = true, message = null) }
        authRepository.completeAuthorization(
            redirectUri = redirectUri,
            onSuccess = { serialized ->
                serializedAuthState = serialized
                val user = authRepository.identityFrom(serialized)
                val personal = _uiState.value.mode == DeviceMode.PERSONAL
                pendingVaultState = if (personal) serialized else null
                _uiState.update {
                    it.copy(
                        busy = false,
                        signedIn = true,
                        user = user,
                        vaultRequest = if (personal) VaultRequest.SEAL else VaultRequest.NONE,
                    )
                }
                loadApplications()
            },
            onError = { message -> _uiState.update { it.copy(busy = false, message = message) } },
        )
    }

    fun consumeVaultRequest() {
        _uiState.update { it.copy(vaultRequest = VaultRequest.NONE) }
    }

    fun createVaultCipher(request: VaultRequest): Cipher = when (request) {
        VaultRequest.SEAL -> vault.createSealCipher()
        VaultRequest.UNLOCK -> vault.createUnlockCipher()
        VaultRequest.NONE -> error("No vault operation requested")
    }

    fun completeVaultRequest(request: VaultRequest, cipher: Cipher) {
        runCatching {
            when (request) {
                VaultRequest.SEAL -> {
                    val state = pendingVaultState ?: error("No session is waiting to be protected")
                    val unlocked = vault.sealNewSession(state, cipher)
                    dataEncryptionKey = unlocked.dataEncryptionKey
                    pendingVaultState = null
                    _uiState.update { it.copy(quickUnlockEnabled = true, message = string(R.string.message_quick_access_enabled)) }
                }

                VaultRequest.UNLOCK -> {
                    val unlocked = vault.unlock(cipher)
                    serializedAuthState = unlocked.serializedAuthState
                    dataEncryptionKey = unlocked.dataEncryptionKey
                    val user = authRepository.identityFrom(unlocked.serializedAuthState)
                    _uiState.update {
                        it.copy(
                            signedIn = true,
                            user = user,
                            quickUnlockEnabled = true,
                            message = null,
                        )
                    }
                    loadApplications()
                }

                VaultRequest.NONE -> Unit
            }
        }.onFailure { error ->
            if (request == VaultRequest.UNLOCK) vault.clear()
            _uiState.update {
                it.copy(
                    quickUnlockEnabled = false,
                    message = string(R.string.message_protected_session_failed, error.message.orEmpty()),
                )
            }
        }
    }

    fun vaultFailed(message: String) {
        _uiState.update {
            it.copy(
                quickUnlockEnabled = vault.hasSession(),
                message = message,
            )
        }
    }

    fun loadApplications() {
        val state = serializedAuthState ?: return
        _uiState.update { it.copy(applicationsLoading = true) }
        authRepository.withFreshAccessToken(
            serializedState = state,
            onSuccess = { token, updatedState ->
                updateSerializedState(updatedState)
                viewModelScope.launch {
                    runCatching { portalRepository.applications(token) }
                        .onSuccess { applications ->
                            _uiState.update { it.copy(applications = applications, applicationsLoading = false) }
                            loadLinkTargetsWithToken(token)
                            syncPushRegistrationWithToken(token)
                            resolvePendingPushAction()
                        }
                        .onFailure { error ->
                            _uiState.update {
                                it.copy(applicationsLoading = false, message = string(R.string.message_apps_load_failed))
                            }
                        }
                }
            },
            onError = { message -> _uiState.update { it.copy(applicationsLoading = false, message = message) } },
        )
    }

    fun enrollDevice(token: String) {
        val mode = _uiState.value.mode ?: return
        if (token.isBlank()) {
            _uiState.update { it.copy(message = string(R.string.message_enter_enrollment)) }
            return
        }
        _uiState.update { it.copy(busy = true, message = null, enrollmentState = EnrollmentState.PENDING) }
        viewModelScope.launch {
            runCatching { deviceService.enroll(token, mode, identity) }
                .onSuccess { result ->
                    preferences.deviceId = result.deviceId
                    preferences.enrollmentState = if (result.trusted) EnrollmentState.TRUSTED else EnrollmentState.PENDING
                    _uiState.update {
                        it.copy(
                            busy = false,
                            deviceId = result.deviceId,
                            enrollmentState = preferences.enrollmentState,
                            enrollmentTokenPrefill = "",
                            message = if (result.trusted) {
                                string(R.string.message_device_approved)
                            } else {
                                string(R.string.message_device_waiting)
                            },
                        )
                    }
                    if (serializedAuthState != null) loadApplications()
                }
                .onFailure { error ->
                    preferences.enrollmentState = EnrollmentState.NOT_ENROLLED
                    _uiState.update {
                        it.copy(busy = false, enrollmentState = EnrollmentState.NOT_ENROLLED, message = error.message)
                    }
                }
        }
    }

    fun enrollDeviceFromQr(value: String) {
        val token = EnrollmentQrParser.tokenFrom(value)
        if (token == null) {
            _uiState.update { it.copy(message = string(R.string.message_qr_invalid)) }
            return
        }
        enrollDevice(token)
    }

    fun acceptEnrollmentLink(uri: Uri?) {
        if (uri?.scheme != "de.missionleben.portal" || uri.host != "enroll") return
        val token = uri.getQueryParameter("token").orEmpty()
        if (token.isNotBlank()) _uiState.update { it.copy(enrollmentTokenPrefill = token) }
    }

    fun openTalkOn(targetId: String, talkUrl: String) {
        val state = serializedAuthState ?: return
        _uiState.update { it.copy(busy = true, message = null) }
        authRepository.withFreshAccessToken(
            serializedState = state,
            onSuccess = { token, updatedState ->
                updateSerializedState(updatedState)
                viewModelScope.launch {
                    runCatching { deviceService.openTalk(token, targetId, talkUrl) }
                        .onSuccess { _uiState.update { it.copy(busy = false, message = string(R.string.message_talk_opened)) } }
                        .onFailure { error -> _uiState.update { it.copy(busy = false, message = error.message) } }
                }
            },
            onError = { message -> _uiState.update { it.copy(busy = false, message = message) } },
        )
    }

    fun logout(onBrowserLogout: (String) -> Unit) {
        val oldState = serializedAuthState
        if (oldState != null) disconnectPushAndRevoke(oldState)
        serializedAuthState = null
        pendingVaultState = null
        dataEncryptionKey = null
        vault.clear()
        clearNotifications()
        _uiState.update {
            it.copy(
                signedIn = false,
                user = null,
                applications = emptyList(),
                linkTargets = emptyList(),
                quickUnlockEnabled = false,
                message = if (it.mode == DeviceMode.SHARED) {
                    string(R.string.message_shared_session_deleted)
                } else {
                    string(R.string.message_signed_out)
                },
            )
        }
        onBrowserLogout(authRepository.endSessionUrl())
    }

    fun clearMessage() = _uiState.update { it.copy(message = null) }

    fun acceptPushAction(value: String?) {
        val action = PushAction.fromWireName(value) ?: return
        pendingPushAction = action
        resolvePendingPushAction()
    }

    fun consumeRequestedUrl() = _uiState.update { it.copy(requestedUrl = null) }

    fun consumeWebDataClearRequest() = _uiState.update { it.copy(clearWebDataRequested = false) }

    fun refreshDeviceStatus() {
        if (!deviceService.endpointDevicesConfigured) return
        val deviceId = preferences.deviceId ?: return
        val mode = preferences.deviceMode ?: return
        viewModelScope.launch {
            runCatching { deviceService.deviceStatus(deviceId, mode, identity) }
                .onSuccess { status ->
                    val oldStatus = preferences.enrollmentState
                    preferences.enrollmentState = status
                    if (status == EnrollmentState.BLOCKED) {
                        val oldState = serializedAuthState
                        if (oldState != null) disconnectPushAndRevoke(oldState)
                        serializedAuthState = null
                        pendingVaultState = null
                        dataEncryptionKey?.fill(0)
                        dataEncryptionKey = null
                        vault.clear()
                        clearNotifications()
                        _uiState.update {
                            it.copy(
                                enrollmentState = status,
                                signedIn = false,
                                user = null,
                                applications = emptyList(),
                                linkTargets = emptyList(),
                                quickUnlockEnabled = false,
                                clearWebDataRequested = true,
                                message = string(R.string.message_device_blocked_clearing),
                            )
                        }
                    } else {
                        _uiState.update { it.copy(enrollmentState = status) }
                        if (status == EnrollmentState.TRUSTED && oldStatus != EnrollmentState.TRUSTED) {
                            syncPushRegistration()
                        }
                    }
                }
        }
    }

    fun setNotificationPrivacy(value: NotificationPrivacy) {
        if (_uiState.value.mode != DeviceMode.PERSONAL) return
        pushStore.personalPrivacy = value
        _uiState.update { it.copy(notificationPrivacy = value) }
        syncPushRegistration()
    }

    fun syncPushRegistration() {
        val state = serializedAuthState ?: return
        authRepository.withFreshAccessToken(
            serializedState = state,
            onSuccess = { token, updatedState ->
                updateSerializedState(updatedState)
                syncPushRegistrationWithToken(token)
            },
            onError = { },
        )
    }

    private fun loadLinkTargetsWithToken(accessToken: String) {
        if (!deviceService.communicationConfigured) return
        viewModelScope.launch {
            runCatching { deviceService.linkTargets(accessToken) }
                .onSuccess { targets -> _uiState.update { it.copy(linkTargets = targets) } }
        }
    }

    private fun syncPushRegistrationWithToken(accessToken: String) {
        if (!deviceService.communicationConfigured || !PushManager.configured) return
        val deviceId = _uiState.value.deviceId ?: return
        if (ContextCompat.checkSelfPermission(
                getApplication(),
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            viewModelScope.launch { runCatching { deviceService.unregisterPush(accessToken, deviceId) } }
            return
        }
        val installationId = PushManager.installationId(getApplication())
        if (installationId == null) {
            viewModelScope.launch { runCatching { deviceService.unregisterPush(accessToken, deviceId) } }
            return
        }
        val mode = _uiState.value.mode ?: return
        val privacy = effectiveNotificationPrivacy(mode)
        viewModelScope.launch {
            runCatching { deviceService.registerPush(accessToken, deviceId, installationId, mode, privacy) }
        }
    }

    private fun disconnectPushAndRevoke(serializedState: String) {
        val registeredDeviceId = preferences.deviceId
        authRepository.withFreshAccessToken(
            serializedState = serializedState,
            onSuccess = { accessToken, updatedState ->
                viewModelScope.launch {
                    if (registeredDeviceId != null) {
                        runCatching { deviceService.unregisterPush(accessToken, registeredDeviceId) }
                    }
                    authRepository.revoke(updatedState)
                }
            },
            onError = { viewModelScope.launch { authRepository.revoke(serializedState) } },
        )
    }

    private fun resolvePendingPushAction() {
        val action = pendingPushAction ?: return
        if (action == PushAction.REFRESH_SECURITY_STATE) {
            pendingPushAction = null
            refreshDeviceStatus()
            _uiState.update { it.copy(message = string(R.string.message_check_device_and_login)) }
            return
        }
        if (!_uiState.value.signedIn) {
            _uiState.update { it.copy(message = string(R.string.message_sign_in_to_open)) }
            return
        }
        val applications = _uiState.value.applications
        if (applications.isEmpty()) {
            if (!_uiState.value.applicationsLoading) {
                pendingPushAction = null
                _uiState.update { it.copy(message = string(R.string.message_app_not_approved)) }
            }
            return
        }
        val application = when (action) {
            PushAction.OPEN_MAIL, PushAction.OPEN_CALENDAR -> applications.firstOrNull {
                it.slug.contains("zimbra", ignoreCase = true) || it.name.contains("zimbra", ignoreCase = true)
            }
            PushAction.OPEN_TALK -> applications.firstOrNull {
                it.slug.contains("talk", ignoreCase = true) || it.name.contains("talk", ignoreCase = true)
            } ?: applications.firstOrNull {
                it.slug.contains("nextcloud", ignoreCase = true) || it.name.contains("nextcloud", ignoreCase = true)
            }
            PushAction.REFRESH_SECURITY_STATE -> null
        }
        pendingPushAction = null
        if (application == null) {
            _uiState.update { it.copy(message = string(R.string.message_app_not_approved)) }
        } else {
            _uiState.update { it.copy(requestedUrl = application.launchUrl, message = null) }
        }
    }

    private fun updateSerializedState(value: String) {
        serializedAuthState = value
        val key = dataEncryptionKey
        if (_uiState.value.mode == DeviceMode.PERSONAL && key != null) {
            runCatching { vault.reseal(value, key) }
        }
    }

    private fun effectiveNotificationPrivacy(mode: DeviceMode?): NotificationPrivacy =
        NotificationPrivacy.effective(mode, pushStore.personalPrivacy)

    private fun clearNotifications() {
        NotificationManagerCompat.from(getApplication<Application>()).cancelAll()
        getApplication<Application>().getSystemService(JobScheduler::class.java).cancelAll()
    }

    private fun string(resourceId: Int, vararg formatArgs: Any): String =
        getApplication<Application>().getString(resourceId, *formatArgs)

    override fun onCleared() {
        dataEncryptionKey?.fill(0)
        authRepository.dispose()
        super.onCleared()
    }
}
