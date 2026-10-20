package de.missionleben.portal.auth

import android.app.Application
import android.content.Intent
import android.net.Uri
import de.missionleben.portal.R
import de.missionleben.portal.web.PortalBrowserActivity
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.ResponseTypeValues
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AuthorizationFailureTest {
    @Test fun browserFailuresAreSeparateFromUnmarkedUserBack() {
        assertNull(PortalBrowserActivity.authorizationFailure(null))
        assertNull(PortalBrowserActivity.authorizationFailure(Intent()))
        assertEquals(AuthorizationFailure.CONTEXT_RESET_FAILED, PortalBrowserActivity.authorizationFailure(
            Intent().putExtra("authorization_failure", "CONTEXT_RESET_FAILED")))
        assertEquals(AuthorizationFailure.INVALID_RESPONSE, PortalBrowserActivity.authorizationFailure(
            Intent().putExtra("authorization_failure", "arbitrary-SECRET")))
    }

    @Test fun serverFailureIsNotPresentedAsUserCancellation() {
        val context = RuntimeEnvironment.getApplication()
        for (code in listOf("access_denied", "server_error", "temporarily_unavailable", "login_required",
            "interaction_required", "consent_required", "unrecognized-SECRET")) {
            val repository = AuthRepository(context)
            try {
                val request = AuthorizationRequest.Builder(AuthorizationServiceConfiguration(
                    Uri.parse("https://example.invalid/authorize"), Uri.parse("https://example.invalid/token")),
                    "synthetic", ResponseTypeValues.CODE, Uri.parse("de.example:/callback"))
                    .setState("synthetic-state").build()
                AuthRepository::class.java.getDeclaredField("pendingAuthorizationRequest").apply {
                    isAccessible = true; set(repository, request)
                }
                var error: String? = null
                repository.completeAuthorization(Uri.parse("de.example:/callback?error=$code&state=synthetic-state&error_description=SECRET"),
                    { fail("Server error must not authenticate") }, { error = it })
                assertEquals(context.getString(AuthorizationFailure.fromOAuth(code).messageResource), error)
                assertNotEquals(context.getString(R.string.auth_cancelled), error)
                assertFalse(error.orEmpty().contains("SECRET"))
            } finally { repository.dispose() }
        }
    }

    @Test fun uncorrelatedErrorDoesNotBypassStateCheck() {
        val context = RuntimeEnvironment.getApplication()
        val repository = AuthRepository(context)
        try {
            val request = AuthorizationRequest.Builder(AuthorizationServiceConfiguration(
                Uri.parse("https://example.invalid/authorize"), Uri.parse("https://example.invalid/token")),
                "synthetic", ResponseTypeValues.CODE, Uri.parse("de.example:/callback"))
                .setState("expected").build()
            AuthRepository::class.java.getDeclaredField("pendingAuthorizationRequest").apply {
                isAccessible = true; set(repository, request)
            }
            var error: String? = null
            repository.completeAuthorization(Uri.parse("de.example:/callback?error=access_denied&state=wrong"),
                { fail("Bad state") }, { error = it })
            assertEquals(context.getString(R.string.auth_state_mismatch), error)
        } finally { repository.dispose() }
    }

    @Test fun successWithoutCodeIsInvalidNotUserCancellation() {
        val context = RuntimeEnvironment.getApplication()
        val repository = AuthRepository(context)
        try {
            val request = AuthorizationRequest.Builder(AuthorizationServiceConfiguration(
                Uri.parse("https://example.invalid/authorize"), Uri.parse("https://example.invalid/token")),
                "synthetic", ResponseTypeValues.CODE, Uri.parse("de.example:/callback"))
                .setState("expected").build()
            AuthRepository::class.java.getDeclaredField("pendingAuthorizationRequest").apply {
                isAccessible = true; set(repository, request)
            }
            var error: String? = null
            repository.completeAuthorization(Uri.parse("de.example:/callback?state=expected"),
                { fail("Missing code") }, { error = it })
            assertEquals(context.getString(R.string.auth_invalid_response), error)
        } finally { repository.dispose() }
    }
}
