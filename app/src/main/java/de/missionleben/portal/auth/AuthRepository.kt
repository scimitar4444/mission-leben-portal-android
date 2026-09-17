package de.missionleben.portal.auth

import android.content.Context
import android.net.Uri
import android.util.Base64
import de.missionleben.portal.BuildConfig
import de.missionleben.portal.model.DeviceMode
import de.missionleben.portal.model.UserIdentity
import net.openid.appauth.AuthState
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.ResponseTypeValues
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class AuthRepository(context: Context) {
    private val authorizationService = AuthorizationService(context)
    private var pendingAuthorizationRequest: AuthorizationRequest? = null

    fun createAuthorizationUrl(
        mode: DeviceMode,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        AuthorizationServiceConfiguration.fetchFromIssuer(Uri.parse(BuildConfig.OIDC_ISSUER)) { configuration, error ->
            if (configuration == null) {
                onError(error?.errorDescription ?: "OIDC-Konfiguration konnte nicht geladen werden.")
                return@fetchFromIssuer
            }

            val scopes = buildList {
                add("openid")
                add("profile")
                add("email")
                add("goauthentik.io/api")
                if (mode == DeviceMode.PERSONAL) add("offline_access")
            }
            val requestBuilder = AuthorizationRequest.Builder(
                configuration,
                BuildConfig.OIDC_CLIENT_ID,
                ResponseTypeValues.CODE,
                Uri.parse(BuildConfig.OIDC_REDIRECT_URI),
            ).setScopes(scopes)

            // A shared tablet must never silently inherit the preceding employee's session.
            if (mode == DeviceMode.SHARED) requestBuilder.setPrompt("login")
            val request = requestBuilder.build()
            pendingAuthorizationRequest = request
            onSuccess(request.toUri().toString())
        }
    }

    fun completeAuthorization(
        redirectUri: Uri,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        val request = pendingAuthorizationRequest
        pendingAuthorizationRequest = null
        if (request == null) {
            onError("Die Anmeldung ist abgelaufen. Bitte erneut anmelden.")
            return
        }
        val authorizationError = AuthorizationException.fromOAuthRedirect(redirectUri)
        if (authorizationError != null) {
            onError(authorizationError.errorDescription ?: "Anmeldung wurde abgebrochen.")
            return
        }
        val response = runCatching { AuthorizationResponse.Builder(request).fromUri(redirectUri).build() }
            .getOrElse {
                onError("Die Antwort von Authentik ist ungültig.")
                return
            }
        if (response.state != request.state) {
            onError("Die Anmeldung konnte nicht eindeutig zugeordnet werden.")
            return
        }

        val state = AuthState(response, authorizationError)
        authorizationService.performTokenRequest(response.createTokenExchangeRequest()) { tokenResponse, tokenError ->
            state.update(tokenResponse, tokenError)
            if (tokenResponse == null) {
                onError(tokenError?.errorDescription ?: "Der Anmeldecode konnte nicht eingelöst werden.")
            } else {
                onSuccess(state.jsonSerializeString())
            }
        }
    }

    fun withFreshAccessToken(
        serializedState: String,
        onSuccess: (accessToken: String, updatedState: String) -> Unit,
        onError: (String) -> Unit,
    ) {
        val state = try {
            AuthState.jsonDeserialize(serializedState)
        } catch (error: Exception) {
            onError("Die gespeicherte Anmeldung ist beschädigt.")
            return
        }
        state.performActionWithFreshTokens(authorizationService) { accessToken, _, error ->
            if (accessToken == null) {
                onError(error?.errorDescription ?: "Die Sitzung konnte nicht erneuert werden.")
            } else {
                onSuccess(accessToken, state.jsonSerializeString())
            }
        }
    }

    fun identityFrom(serializedState: String): UserIdentity {
        val state = AuthState.jsonDeserialize(serializedState)
        val claims = decodeJwtPayload(state.idToken)
        return UserIdentity(
            subject = claims.optString("sub"),
            displayName = claims.optString("name")
                .ifBlank { claims.optString("preferred_username") }
                .ifBlank { claims.optString("email") }
                .ifBlank { "Mitarbeiter:in" },
            email = claims.optString("email"),
        )
    }

    fun endSessionUrl(): String = BuildConfig.OIDC_ISSUER.trimEnd('/') + "/end-session/"

    suspend fun revoke(serializedState: String) = withContext(Dispatchers.IO) {
        val state = runCatching { AuthState.jsonDeserialize(serializedState) }.getOrNull() ?: return@withContext
        listOfNotNull(state.refreshToken, state.accessToken).forEach { token ->
            runCatching {
                val connection = URL(BuildConfig.AUTHENTIK_BASE_URL.trimEnd('/') + "/application/o/revoke/")
                    .openConnection() as HttpURLConnection
                try {
                    connection.requestMethod = "POST"
                    connection.connectTimeout = 8_000
                    connection.readTimeout = 8_000
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                    val form = "token=${URLEncoder.encode(token, Charsets.UTF_8.name())}" +
                        "&client_id=${URLEncoder.encode(BuildConfig.OIDC_CLIENT_ID, Charsets.UTF_8.name())}"
                    connection.outputStream.bufferedWriter().use { it.write(form) }
                    connection.inputStream?.close()
                } finally {
                    connection.disconnect()
                }
            }
        }
    }

    fun dispose() = authorizationService.dispose()

    private fun decodeJwtPayload(jwt: String?): JSONObject {
        if (jwt.isNullOrBlank()) return JSONObject()
        return try {
            val payload = jwt.split('.').getOrNull(1) ?: return JSONObject()
            val decoded = Base64.decode(payload, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
            JSONObject(decoded.toString(Charsets.UTF_8))
        } catch (_: Exception) {
            JSONObject()
        }
    }
}
