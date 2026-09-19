package de.missionleben.portal.auth

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.util.Log
import de.missionleben.portal.BuildConfig
import de.missionleben.portal.R
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

private const val AUTH_LOG_TAG = "MissionLebenAuth"

class AuthRepository(context: Context) {
    private val context = context.applicationContext
    private val authorizationService = AuthorizationService(context)
    private var pendingAuthorizationRequest: AuthorizationRequest? = null

    fun createAuthorizationUrl(
        mode: DeviceMode,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        AuthorizationServiceConfiguration.fetchFromIssuer(Uri.parse(BuildConfig.OIDC_ISSUER)) { configuration, error ->
            if (configuration == null) {
                onError(this.context.getString(R.string.auth_oidc_config_failed))
                return@fetchFromIssuer
            }

            val scopes = buildList {
                add("openid")
                add("profile")
                add("email")
                add("goauthentik.io/api")
                if (mode == DeviceMode.PERSONAL) add("offline_access")
            }
            val request = AuthorizationRequest.Builder(
                configuration,
                BuildConfig.OIDC_CLIENT_ID,
                ResponseTypeValues.CODE,
                Uri.parse(BuildConfig.OIDC_REDIRECT_URI),
            ).setScopes(scopes).build()
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
            Log.w(AUTH_LOG_TAG, "Authorization response rejected: pending request missing")
            onError(context.getString(R.string.auth_expired))
            return
        }
        val authorizationError = if (redirectUri.getQueryParameter("error") != null) {
            AuthorizationException.fromOAuthRedirect(redirectUri)
        } else {
            null
        }
        if (authorizationError != null) {
            Log.w(
                AUTH_LOG_TAG,
                "Authorization server returned an error: type=${authorizationError.type}, " +
                    "code=${authorizationError.code}",
            )
            onError(context.getString(R.string.auth_cancelled))
            return
        }
        val response = runCatching { AuthorizationResponse.Builder(request).fromUri(redirectUri).build() }
            .getOrElse {
                Log.w(AUTH_LOG_TAG, "Authorization response could not be parsed", it)
                onError(context.getString(R.string.auth_invalid_response))
                return
            }
        if (response.state != request.state) {
            Log.w(AUTH_LOG_TAG, "Authorization response rejected: state mismatch")
            onError(context.getString(R.string.auth_state_mismatch))
            return
        }

        val state = AuthState(response, authorizationError)
        authorizationService.performTokenRequest(response.createTokenExchangeRequest()) { tokenResponse, tokenError ->
            state.update(tokenResponse, tokenError)
            if (tokenResponse == null) {
                Log.w(
                    AUTH_LOG_TAG,
                    "Authorization code exchange failed: type=${tokenError?.type}, code=${tokenError?.code}",
                )
                onError(context.getString(R.string.auth_code_exchange_failed))
            } else {
                Log.i(AUTH_LOG_TAG, "Authorization completed successfully")
                onSuccess(state.jsonSerializeString())
            }
        }
    }

    fun withFreshAccessToken(
        serializedState: String,
        onSuccess: (accessToken: String, updatedState: String) -> Unit,
        onError: (AccessTokenFailure) -> Unit,
    ) {
        val state = try {
            AuthState.jsonDeserialize(serializedState)
        } catch (error: Exception) {
            onError(
                AccessTokenFailure(
                    message = context.getString(R.string.auth_saved_session_invalid),
                    reauthenticationRequired = true,
                ),
            )
            return
        }
        if (!state.isAuthorized || (state.needsTokenRefresh && state.refreshToken.isNullOrBlank())) {
            onError(
                AccessTokenFailure(
                    message = context.getString(R.string.auth_session_refresh_failed),
                    reauthenticationRequired = true,
                ),
            )
            return
        }
        runCatching {
            state.performActionWithFreshTokens(authorizationService) { accessToken, _, error ->
                if (accessToken == null) {
                    onError(
                        AccessTokenFailure(
                            message = context.getString(R.string.auth_session_refresh_failed),
                            reauthenticationRequired = TokenRefreshFailurePolicy.requiresReauthentication(
                                oauthError = error?.error,
                                stateAuthorized = state.isAuthorized,
                            ),
                        ),
                    )
                } else {
                    onSuccess(accessToken, state.jsonSerializeString())
                }
            }
        }.onFailure {
            onError(
                AccessTokenFailure(
                    message = context.getString(R.string.auth_session_refresh_failed),
                    reauthenticationRequired = !state.isAuthorized ||
                        (state.needsTokenRefresh && state.refreshToken.isNullOrBlank()),
                ),
            )
        }
    }

    fun identityFrom(serializedState: String): UserIdentity {
        val state = AuthState.jsonDeserialize(serializedState)
        val claims = decodeJwtPayload(state.idToken)
        return UserIdentity(
            subject = claims.optString("sub"),
            displayName = IdentityDisplayName.select(
                givenName = claims.optString("given_name"),
                fullName = claims.optString("name"),
                preferredUsername = claims.optString("preferred_username"),
                email = claims.optString("email"),
                fallback = context.getString(R.string.employee_fallback),
            ),
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
