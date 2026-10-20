package de.missionleben.portal.auth

import de.missionleben.portal.R

/** Only a closed, recognized category may become user-visible; never server descriptions. */
enum class AuthorizationFailure(val messageResource: Int) {
    SERVER_REJECTED(R.string.auth_server_rejected),
    SERVER_UNAVAILABLE(R.string.auth_server_unavailable),
    LOGIN_REQUIRED(R.string.auth_server_login_required),
    SERVER_ERROR(R.string.auth_server_error),
    CONTEXT_RESET_FAILED(R.string.auth_context_clear_failed),
    LOCAL_LOCK_REQUIRED(R.string.auth_fresh_login_required),
    BROWSER_UNAVAILABLE(R.string.browser_webview_missing),
    INVALID_RESPONSE(R.string.auth_invalid_response);

    companion object {
        fun fromOAuth(error: String): AuthorizationFailure = when (error) {
            "access_denied" -> SERVER_REJECTED
            "server_error", "temporarily_unavailable" -> SERVER_UNAVAILABLE
            "login_required", "interaction_required", "consent_required" -> LOGIN_REQUIRED
            else -> SERVER_ERROR
        }
    }
}
