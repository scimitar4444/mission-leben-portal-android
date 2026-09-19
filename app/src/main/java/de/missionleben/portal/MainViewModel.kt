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
import de.missionleben.portal.auth.AccessTokenFailure
import de.missionleben.portal.auth.AuthRepository
import de.missionleben.portal.auth.ReauthenticationPolicy
import de.missionleben.portal.data.AppPreferences
import de.missionleben.portal.data.PortalAuthenticationException
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
import de.missionleben.portal.update.UpdatePolicy
import de.missionleben.portal.update.UpdateRepository
import de.missionleben.portal.update.UpdateStatus
import java.io.File
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
    private val updateRepository = UpdateRepository(application)

    private var serializedAuthState: String? = null
    private var dataEncryptionKey: ByteArray? = null
    private var pendingVaultState: String? = null
    private var pendingPushAction: PushAction? = null
    private var downloadedUpdateFile: File? = null

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
            reauthenticationRequired = preferences.reauthenticationRequired,
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
        val pendingEnrollmentToken = _uiState.value.enrollmentTokenPrefill
        val syncExistingDevice = pendingEnrollmentToken.isBlank() &&
            preferences.deviceId != null &&
            deviceService.endpointDevicesConfigured
        if (preferences.deviceMode != mode) {
            serializedAuthState = null
            dataEncryptionKey = null
            vault.clear()
            preferences.clearReauthentication()
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
                reauthenticationRequired = false,
                notificationPrivacy = effectiveNotificationPrivacy(mode),
                busy = syncExistingDevice,
                message = null,
            )
        }
        when {
            pendingEnrollmentToken.isNotBlank() -> enrollDevice(pendingEnrollmentToken)
            syncExistingDevice -> syncDeviceStatus(blockLogin = true)
        }
    }

    fun resetProfile() {
        val oldState = serializedAuthState
        if (oldState != null) disconnectPushAndRevoke(oldState)
        serializedAuthState = null
        pendingVaultState = null
        dataEncryptionKey = null
        vault.clear()
        preferences.clearReauthentication()
        clearNotifications()
        preferences.deviceMode = null
        _uiState.value = UiState(
            enrollmentState = preferences.enrollmentState,
            deviceId = preferences.deviceId,
            deviceKeyId = identity.keyId(),
            deviceServiceConfigured = deviceService.endpointDevicesConfigured,
            communicationServiceConfigured = deviceService.communicationConfigured,
            pushConfigured = PushManager.configured,
            notificationPrivacy = NotificationPrivacy.MINIMAL,
        )
    }

    fun createLoginUrl(onSuccess: (String) -> Unit) {
        val mode = _uiState.value.mode ?: return
        val reauthentication = ReauthenticationPolicy.request(
            mode = mode,
            enrollmentState = _uiState.value.enrollmentState,
            reauthenticationRequired = preferences.reauthenticationRequired,
            storedLoginHint = preferences.reauthenticationHint,
        )
        _uiState.update { it.copy(busy = true, message = null) }
        authRepository.createAuthorizationUrl(
            mode = mode,
            loginHint = reauthentication.loginHint,
            forceReauthentication = reauthentication.forceLogin,
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
                if (personal) {
                    preferences.reauthenticationHint = user.loginHint
                    preferences.reauthenticationRequired = false
                } else {
                    preferences.clearReauthentication()
                }
                pendingVaultState = if (personal) serialized else null
                _uiState.update {
                    it.copy(
                        busy = false,
                        signedIn = true,
                        user = user,
                        reauthenticationRequired = false,
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
                    if (ReauthenticationPolicy.hasReachedAbsoluteDeadline(user.authenticatedAtEpochSeconds)) {
                        _uiState.update { it.copy(user = user) }
                        sessionExpired()
                        return
                    }
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
        if (expireAtAbsoluteDeadline()) return
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
                            if (error is PortalAuthenticationException) {
                                sessionExpired()
                            } else {
                                _uiState.update {
                                    it.copy(applicationsLoading = false, message = string(R.string.message_apps_load_failed))
                                }
                            }
                        }
                }
            },
            onError = { failure ->
                handleAccessTokenFailure(failure) { it.copy(applicationsLoading = false) }
            },
        )
    }

    fun openApplication(url: String) {
        if (expireAtAbsoluteDeadline()) return
        val state = serializedAuthState
        if (!_uiState.value.signedIn || state == null) {
            _uiState.update { it.copy(message = string(R.string.message_sign_in_to_open)) }
            return
        }
        _uiState.update { it.copy(busy = true, message = null) }
        authRepository.withFreshAccessToken(
            serializedState = state,
            onSuccess = { _, updatedState ->
                updateSerializedState(updatedState)
                _uiState.update { it.copy(busy = false, requestedUrl = url) }
            },
            onError = { failure ->
                handleAccessTokenFailure(failure) { it.copy(busy = false) }
            },
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
        if (token.isBlank()) return
        if (_uiState.value.mode == null) {
            _uiState.update { it.copy(enrollmentTokenPrefill = token) }
        } else {
            enrollDevice(token)
        }
    }

    fun openTalkOn(targetId: String, talkUrl: String) {
        if (expireAtAbsoluteDeadline()) return
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
            onError = { failure ->
                handleAccessTokenFailure(failure) { it.copy(busy = false) }
            },
        )
    }

    fun sessionExpired() {
        val expiredState = _uiState.value
        val boundDeviceReauthenticationAvailable = ReauthenticationPolicy.canOfferBoundDeviceReauthentication(
            mode = expiredState.mode,
            enrollmentState = expiredState.enrollmentState,
            loginHint = expiredState.user?.loginHint,
        )
        if (boundDeviceReauthenticationAvailable) {
            preferences.reauthenticationHint = expiredState.user?.loginHint
            preferences.reauthenticationRequired = true
        } else {
            preferences.clearReauthentication()
        }
        serializedAuthState = null
        pendingVaultState = null
        pendingPushAction = null
        dataEncryptionKey?.fill(0)
        dataEncryptionKey = null
        vault.clear()
        clearNotifications()
        PushManager.unregister()
        _uiState.update {
            it.copy(
                busy = true,
                signedIn = false,
                user = null,
                reauthenticationRequired = boundDeviceReauthenticationAvailable,
                quickUnlockEnabled = false,
                vaultRequest = VaultRequest.NONE,
                applications = emptyList(),
                applicationsLoading = false,
                linkTargets = emptyList(),
                requestedUrl = null,
                clearWebDataRequested = true,
                message = if (boundDeviceReauthenticationAvailable) {
                    string(R.string.message_session_reauth_required)
                } else {
                    string(R.string.message_session_expired)
                },
            )
        }
    }

    fun logout(onBrowserLogout: (String) -> Unit) {
        val oldState = serializedAuthState
        if (oldState != null) disconnectPushAndRevoke(oldState)
        serializedAuthState = null
        pendingVaultState = null
        dataEncryptionKey = null
        vault.clear()
        preferences.clearReauthentication()
        clearNotifications()
        _uiState.update {
            it.copy(
                signedIn = false,
                user = null,
                reauthenticationRequired = false,
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

    fun checkForUpdates(force: Boolean = false) {
        val status = _uiState.value.updateStatus
        if (status == UpdateStatus.CHECKING || status == UpdateStatus.DOWNLOADING || status == UpdateStatus.READY) {
            return
        }
        val now = System.currentTimeMillis()
        if (!force && !UpdatePolicy.shouldCheck(preferences.lastUpdateCheckEpochMillis, now)) return
        _uiState.update { it.copy(updateStatus = UpdateStatus.CHECKING) }
        viewModelScope.launch {
            runCatching { updateRepository.checkForUpdate() }
                .onSuccess { update ->
                    preferences.lastUpdateCheckEpochMillis = now
                    _uiState.update {
                        it.copy(
                            availableUpdate = update,
                            updateStatus = if (update == null) UpdateStatus.IDLE else UpdateStatus.AVAILABLE,
                        )
                    }
                }
                .onFailure {
                    _uiState.update { state ->
                        state.copy(
                            updateStatus = UpdateStatus.IDLE,
                            message = if (force) string(R.string.update_check_failed) else state.message,
                        )
                    }
                }
        }
    }

    fun downloadUpdate() {
        val update = _uiState.value.availableUpdate ?: return
        val cachedFile = downloadedUpdateFile
        if (cachedFile?.isFile == true) {
            _uiState.update { it.copy(updateStatus = UpdateStatus.READY) }
            return
        }
        if (_uiState.value.updateStatus == UpdateStatus.DOWNLOADING) return
        _uiState.update { it.copy(updateStatus = UpdateStatus.DOWNLOADING, message = null) }
        viewModelScope.launch {
            runCatching { updateRepository.downloadAndVerify(update) }
                .onSuccess { file ->
                    downloadedUpdateFile = file
                    _uiState.update { it.copy(updateStatus = UpdateStatus.READY) }
                }
                .onFailure {
                    downloadedUpdateFile = null
                    _uiState.update {
                        it.copy(
                            updateStatus = UpdateStatus.AVAILABLE,
                            message = string(R.string.update_download_failed),
                        )
                    }
                }
        }
    }

    fun readyUpdateFile(): File? = downloadedUpdateFile

    fun updateInstallStarted() {
        downloadedUpdateFile = null
        _uiState.update { it.copy(availableUpdate = null, updateStatus = UpdateStatus.IDLE) }
    }

    fun updateInstallPermissionDenied() {
        _uiState.update {
            it.copy(
                updateStatus = UpdateStatus.AVAILABLE,
                message = string(R.string.update_install_permission_denied),
            )
        }
    }

    fun updateInstallFailed() {
        _uiState.update {
            it.copy(
                updateStatus = UpdateStatus.AVAILABLE,
                message = string(R.string.update_installer_unavailable),
            )
        }
    }

    fun dismissUpdate() {
        if (_uiState.value.updateStatus == UpdateStatus.DOWNLOADING) return
        downloadedUpdateFile?.delete()
        downloadedUpdateFile = null
        _uiState.update { it.copy(availableUpdate = null, updateStatus = UpdateStatus.IDLE) }
    }

    fun acceptPushAction(value: String?) {
        val action = PushAction.fromWireName(value) ?: return
        pendingPushAction = action
        resolvePendingPushAction()
    }

    fun consumeRequestedUrl() = _uiState.update { it.copy(requestedUrl = null) }

    fun consumeWebDataClearRequest() = _uiState.update {
        it.copy(clearWebDataRequested = false, busy = false)
    }

    fun refreshDeviceStatus() {
        syncDeviceStatus(blockLogin = false)
    }

    private fun syncDeviceStatus(blockLogin: Boolean) {
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
                        preferences.clearReauthentication()
                        clearNotifications()
                        _uiState.update {
                            it.copy(
                                enrollmentState = status,
                                signedIn = false,
                                user = null,
                                reauthenticationRequired = false,
                                applications = emptyList(),
                                linkTargets = emptyList(),
                                quickUnlockEnabled = false,
                                busy = false,
                                clearWebDataRequested = true,
                                message = string(R.string.message_device_blocked_clearing),
                            )
                        }
                    } else {
                        _uiState.update { it.copy(enrollmentState = status, busy = false) }
                        if (status == EnrollmentState.TRUSTED && oldStatus != EnrollmentState.TRUSTED) {
                            syncPushRegistration()
                        }
                    }
                }
                .onFailure { error ->
                    if (blockLogin) {
                        _uiState.update { it.copy(busy = false, message = error.message) }
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
        if (expireAtAbsoluteDeadline()) return
        val state = serializedAuthState ?: return
        authRepository.withFreshAccessToken(
            serializedState = state,
            onSuccess = { token, updatedState ->
                updateSerializedState(updatedState)
                syncPushRegistrationWithToken(token)
            },
            onError = { failure ->
                if (failure.reauthenticationRequired) sessionExpired()
            },
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
            openApplication(application.launchUrl)
        }
    }

    private fun handleAccessTokenFailure(
        failure: AccessTokenFailure,
        onTransientFailure: (UiState) -> UiState,
    ) {
        if (failure.reauthenticationRequired) {
            sessionExpired()
        } else {
            _uiState.update { onTransientFailure(it).copy(message = failure.message) }
        }
    }

    private fun expireAtAbsoluteDeadline(): Boolean {
        val user = _uiState.value.user ?: return false
        if (!ReauthenticationPolicy.hasReachedAbsoluteDeadline(user.authenticatedAtEpochSeconds)) {
            return false
        }
        sessionExpired()
        return true
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
