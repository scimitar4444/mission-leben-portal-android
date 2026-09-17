package de.missionleben.portal

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.missionleben.portal.auth.AuthRepository
import de.missionleben.portal.data.AppPreferences
import de.missionleben.portal.data.PortalRepository
import de.missionleben.portal.device.DeviceServiceRepository
import de.missionleben.portal.model.DeviceMode
import de.missionleben.portal.model.EnrollmentState
import de.missionleben.portal.model.UiState
import de.missionleben.portal.model.VaultRequest
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
    private val deviceService = DeviceServiceRepository()

    private var serializedAuthState: String? = null
    private var dataEncryptionKey: ByteArray? = null
    private var pendingVaultState: String? = null

    private val _uiState = MutableStateFlow(
        UiState(
            mode = preferences.deviceMode,
            enrollmentState = preferences.enrollmentState,
            deviceId = preferences.deviceId,
            deviceKeyId = identity.keyId(),
            deviceServiceConfigured = deviceService.configured,
            quickUnlockEnabled = vault.hasSession(),
        ),
    )
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        if (preferences.deviceMode == DeviceMode.PERSONAL && vault.hasSession()) {
            _uiState.update { it.copy(vaultRequest = VaultRequest.UNLOCK) }
        }
    }

    fun selectMode(mode: DeviceMode) {
        if (preferences.deviceMode != mode) {
            serializedAuthState = null
            dataEncryptionKey = null
            vault.clear()
        }
        preferences.deviceMode = mode
        _uiState.update {
            it.copy(
                mode = mode,
                signedIn = false,
                user = null,
                applications = emptyList(),
                quickUnlockEnabled = false,
                message = null,
            )
        }
    }

    fun resetProfile() {
        val oldState = serializedAuthState
        if (oldState != null) viewModelScope.launch { authRepository.revoke(oldState) }
        serializedAuthState = null
        pendingVaultState = null
        dataEncryptionKey = null
        vault.clear()
        preferences.clearProfile()
        _uiState.value = UiState(
            deviceKeyId = identity.keyId(),
            deviceServiceConfigured = deviceService.configured,
        )
    }

    fun createLoginIntent(onSuccess: (Intent) -> Unit) {
        val mode = _uiState.value.mode ?: return
        _uiState.update { it.copy(busy = true, message = null) }
        authRepository.createAuthorizationIntent(
            mode = mode,
            onSuccess = {
                _uiState.update { state -> state.copy(busy = false) }
                onSuccess(it)
            },
            onError = { message -> _uiState.update { it.copy(busy = false, message = message) } },
        )
    }

    fun completeAuthorization(data: Intent?) {
        if (data == null) {
            _uiState.update { it.copy(message = "Anmeldung wurde abgebrochen.") }
            return
        }
        _uiState.update { it.copy(busy = true, message = null) }
        authRepository.completeAuthorization(
            data = data,
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
                    _uiState.update { it.copy(quickUnlockEnabled = true, message = "Schnellzugang ist aktiviert.") }
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
                    message = "Geschützte Sitzung konnte nicht geöffnet werden: ${error.message}",
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
                        }
                        .onFailure { error ->
                            _uiState.update {
                                it.copy(applicationsLoading = false, message = error.message ?: "Apps konnten nicht geladen werden.")
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
            _uiState.update { it.copy(message = "Bitte Enrollment-Code eingeben oder QR-Link öffnen.") }
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
                            message = if (result.trusted) "Gerät wurde freigegeben." else "Gerät wartet auf die Freigabe in Authentik.",
                        )
                    }
                }
                .onFailure { error ->
                    preferences.enrollmentState = EnrollmentState.NOT_ENROLLED
                    _uiState.update {
                        it.copy(busy = false, enrollmentState = EnrollmentState.NOT_ENROLLED, message = error.message)
                    }
                }
        }
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
                        .onSuccess { _uiState.update { it.copy(busy = false, message = "Talk wird am Zielgerät geöffnet.") } }
                        .onFailure { error -> _uiState.update { it.copy(busy = false, message = error.message) } }
                }
            },
            onError = { message -> _uiState.update { it.copy(busy = false, message = message) } },
        )
    }

    fun logout(onBrowserLogout: (String) -> Unit) {
        val oldState = serializedAuthState
        if (oldState != null) viewModelScope.launch { authRepository.revoke(oldState) }
        serializedAuthState = null
        pendingVaultState = null
        dataEncryptionKey = null
        vault.clear()
        _uiState.update {
            it.copy(
                signedIn = false,
                user = null,
                applications = emptyList(),
                linkTargets = emptyList(),
                quickUnlockEnabled = false,
                message = if (it.mode == DeviceMode.SHARED) "Sitzung auf diesem Tablet wurde gelöscht." else "Abgemeldet.",
            )
        }
        onBrowserLogout(authRepository.endSessionUrl())
    }

    fun clearMessage() = _uiState.update { it.copy(message = null) }

    private fun loadLinkTargetsWithToken(accessToken: String) {
        if (!deviceService.configured) return
        viewModelScope.launch {
            runCatching { deviceService.linkTargets(accessToken) }
                .onSuccess { targets -> _uiState.update { it.copy(linkTargets = targets) } }
        }
    }

    private fun updateSerializedState(value: String) {
        serializedAuthState = value
        val key = dataEncryptionKey
        if (_uiState.value.mode == DeviceMode.PERSONAL && key != null) {
            runCatching { vault.reseal(value, key) }
        }
    }

    override fun onCleared() {
        dataEncryptionKey?.fill(0)
        authRepository.dispose()
        super.onCleared()
    }
}
