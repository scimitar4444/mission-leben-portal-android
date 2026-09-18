package de.missionleben.portal

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Bundle
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
import de.missionleben.portal.model.VaultRequest
import de.missionleben.portal.push.PortalFirebaseMessagingService
import de.missionleben.portal.push.PushManager
import de.missionleben.portal.push.PushRegistrationStore
import de.missionleben.portal.ui.MissionLebenApp
import de.missionleben.portal.ui.MissionLebenTheme
import de.missionleben.portal.web.PortalBrowserActivity

class MainActivity : FragmentActivity() {
    private val viewModel: MainViewModel by viewModels()

    private val pushRegistrationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == PortalFirebaseMessagingService.ACTION_REGISTRATION_CHANGED) {
                viewModel.syncPushRegistration()
            }
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) PushManager.register()
    }

    private val authorizationLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        viewModel.completeAuthorization(result.data?.data)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel.acceptEnrollmentLink(intent?.data)
        viewModel.acceptPushAction(intent?.getStringExtra(PortalFirebaseMessagingService.EXTRA_PUSH_ACTION))

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
                MissionLebenApp(
                    state = state,
                    onSelectMode = { mode ->
                        PortalBrowserActivity.clearLocalWebData(this@MainActivity) {
                            viewModel.selectMode(mode)
                        }
                    },
                    onStartLogin = {
                        state.mode?.let { mode ->
                            viewModel.createLoginUrl { url ->
                                authorizationLauncher.launch(
                                    PortalBrowserActivity.authorizationIntent(this@MainActivity, url, mode),
                                )
                            }
                        }
                    },
                    onOpenUrl = ::openUrl,
                    onReloadApplications = viewModel::loadApplications,
                    onEnrollDevice = viewModel::enrollDevice,
                    onRefreshDeviceStatus = viewModel::refreshDeviceStatus,
                    onOpenTalk = viewModel::openTalkOn,
                    onNotificationPrivacyChange = viewModel::setNotificationPrivacy,
                    onLogout = { viewModel.logout(::openLogout) },
                    onResetProfile = {
                        PortalBrowserActivity.clearLocalWebData(this@MainActivity) {
                            viewModel.resetProfile()
                        }
                    },
                    onDismissMessage = viewModel::clearMessage,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        viewModel.acceptEnrollmentLink(intent.data)
        viewModel.acceptPushAction(intent.getStringExtra(PortalFirebaseMessagingService.EXTRA_PUSH_ACTION))
    }

    override fun onStart() {
        super.onStart()
        viewModel.refreshDeviceStatus()
        ContextCompat.registerReceiver(
            this,
            pushRegistrationReceiver,
            IntentFilter(PortalFirebaseMessagingService.ACTION_REGISTRATION_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onStop() {
        unregisterReceiver(pushRegistrationReceiver)
        super.onStop()
    }

    private fun requestNotificationPermissionAfterLogin(signedIn: Boolean) {
        if (!signedIn || !PushManager.configured) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            PushManager.register()
            return
        }
        val store = PushRegistrationStore(this)
        if (store.permissionWasRequested) return
        store.permissionWasRequested = true
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun openUrl(url: String) {
        startActivity(PortalBrowserActivity.appIntent(this, url))
    }

    private fun openLogout(url: String) {
        startActivity(PortalBrowserActivity.logoutIntent(this, url))
    }

    private fun handleVaultRequest(request: VaultRequest) {
        viewModel.consumeVaultRequest()
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
        if (BiometricManager.from(this).canAuthenticate(authenticators) != BiometricManager.BIOMETRIC_SUCCESS) {
            viewModel.vaultFailed("Bitte Biometrie oder eine sichere Displaysperre einrichten.")
            return
        }

        val cipher = runCatching { viewModel.createVaultCipher(request) }.getOrElse {
            viewModel.vaultFailed("Sicherer Gerätespeicher ist nicht verfügbar: ${it.message}")
            return
        }
        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    val authorizedCipher = result.cryptoObject?.cipher
                    if (authorizedCipher == null) {
                        viewModel.vaultFailed("Der sichere Schlüssel wurde nicht freigegeben.")
                    } else {
                        viewModel.completeVaultRequest(request, authorizedCipher)
                    }
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    viewModel.vaultFailed("Schnellzugang nicht geöffnet: $errString")
                }
            },
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(if (request == VaultRequest.SEAL) "Schnellzugang aktivieren" else "Mission Leben Portal öffnen")
            .setSubtitle("Fingerabdruck, Gesicht oder Gerätecode verwenden")
            .setAllowedAuthenticators(authenticators)
            .build()
        prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher))
    }
}
