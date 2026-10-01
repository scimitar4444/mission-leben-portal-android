package de.missionleben.portal.auth

import android.app.Application
import android.net.Uri
import android.util.Base64
import de.missionleben.portal.model.DeviceMode
import net.openid.appauth.AuthState
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.TokenRequest
import net.openid.appauth.TokenResponse
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Synthetic claim parsing only: not a substitute for AppAuth validation or a server login. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AuthRepositoryEvidenceTest {
    private val repository = AuthRepository(RuntimeEnvironment.getApplication())
    private val configuration = AuthorizationServiceConfiguration(
        Uri.parse("https://example.invalid/authorize"), Uri.parse("https://example.invalid/token"),
    )

    @After fun dispose() = repository.dispose()

    private fun state(claims: JSONObject, refreshToken: String? = null): String {
        val request = TokenRequest.Builder(configuration, "synthetic-client")
            .setGrantType("authorization_code").setAuthorizationCode("synthetic-never-sent")
            .setRedirectUri(Uri.parse("de.example:/callback")).build()
        fun encode(value: String) = Base64.encodeToString(value.toByteArray(),
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        val jwt = encode("{\"alg\":\"RS256\"}") + "." + encode(claims.toString()) + ".synthetic"
        val response = TokenResponse.Builder(request).setTokenType("Bearer")
            .setAccessToken("synthetic-never-sent").setIdToken(jwt).setRefreshToken(refreshToken).build()
        return AuthState(configuration).apply { update(response, null) }.jsonSerializeString()
    }

    @Test fun freshIssuedTokenDoesNotRefreshOldAuthenticationTime() {
        val serialized = state(JSONObject().put("auth_time", 100L).put("iat", 900_000L).put("sub", "subject"))
        assertEquals(100L, repository.identityFrom(serialized).authenticatedAtEpochSeconds)
        assertEquals(100L, repository.authenticationEvidence(serialized).authenticatedAtEpochSeconds)
    }

    @Test fun missingAuthenticationTimeNeverFallsBackToIssueTime() {
        val serialized = state(JSONObject().put("iat", 900_000L).put("sub", "subject"))
        assertEquals(0L, repository.identityFrom(serialized).authenticatedAtEpochSeconds)
        assertEquals(0L, repository.authenticationEvidence(serialized).authenticatedAtEpochSeconds)
    }

    @Test fun invalidOrNegativeAuthenticationTimeHasNoFreshnessEvidence() {
        listOf<Any>("not-a-time", -1L, JSONObject.NULL).forEach { value ->
            assertEquals(0L, repository.authenticationEvidence(state(JSONObject().put("auth_time", value)))
                .authenticatedAtEpochSeconds)
        }
    }

    @Test fun usernameHintIsConfirmedUsernameNotMailboxAddress() {
        val noUsername = state(JSONObject().put("email", "mailbox@example.invalid"))
        assertEquals("", repository.identityFrom(noUsername).loginHint)
        val named = state(JSONObject().put("preferred_username", " person.example ")
            .put("email", "different@example.invalid"))
        assertEquals("person.example", repository.identityFrom(named).loginHint)
    }

    @Test fun arrayAudienceAndAuthorizedPartyAreKeptForIndependentChecks() {
        val evidence = repository.authenticationEvidence(state(JSONObject()
            .put("iss", "https://example.invalid/").put("aud", JSONArray(listOf("first", "second")))
            .put("azp", "second").put("sub", "subject")))
        assertEquals(setOf("first", "second"), evidence.audiences)
        assertEquals("second", evidence.authorizedParty)
        assertEquals("subject", evidence.subject)
        assertEquals("https://example.invalid/", evidence.issuer)
    }

    @Test fun scalarAudienceAndRefreshPresenceAreKept() {
        val claims = JSONObject().put("aud", "single")
        assertEquals(setOf("single"), repository.authenticationEvidence(state(claims)).audiences)
        assertFalse(repository.authenticationEvidence(state(claims)).hasRefreshToken)
        assertTrue(repository.authenticationEvidence(state(claims, "synthetic-refresh-never-sent")).hasRefreshToken)
    }

    @Test fun onlyProtectedPersonalSessionsRequestOfflineAccess() {
        assertTrue("offline_access" in AuthRepository.authorizationScopes(DeviceMode.PERSONAL, true))
        assertFalse("offline_access" in AuthRepository.authorizationScopes(DeviceMode.PERSONAL, false))
        assertFalse("offline_access" in AuthRepository.authorizationScopes(DeviceMode.SHARED, true))
        assertFalse("offline_access" in AuthRepository.authorizationScopes(DeviceMode.SHARED, false))
    }

    @Test fun ninetyDayRequestUsesOnlyTheExplicitRoutingParameterAndFreshPkceStateNonce() {
        fun request() = AuthRepository.buildAuthorizationRequest(configuration, DeviceMode.PERSONAL,
            "person.example", false, true, true)
        val first = request()
        val second = request()
        assertEquals("90d", first.toUri().getQueryParameter("ml_reauth"))
        assertNull(first.toUri().getQueryParameter("prompt"))
        assertNull(first.toUri().getQueryParameter("max_age"))
        assertEquals("S256", first.codeVerifierChallengeMethod)
        assertTrue(first.codeVerifier!!.length >= 43)
        assertNotEquals(first.state, second.state)
        assertNotEquals(first.nonce, second.nonce)
        assertNotEquals(first.codeVerifier, second.codeVerifier)
    }

    @Test fun regularFallbackSharedAndMissingHintNeverReceiveNinetyDayRouting() {
        val normal = AuthRepository.buildAuthorizationRequest(configuration, DeviceMode.PERSONAL,
            "person.example", false, true, false)
        val shared = AuthRepository.buildAuthorizationRequest(configuration, DeviceMode.SHARED,
            null, false, false, true)
        val missingHint = AuthRepository.buildAuthorizationRequest(configuration, DeviceMode.PERSONAL,
            null, false, false, true)
        listOf(normal, shared, missingHint).forEach {
            assertNull(it.toUri().getQueryParameter("ml_reauth"))
        }
        assertFalse(shared.toUri().getQueryParameter("scope")!!.contains("offline_access"))
        assertFalse(missingHint.toUri().getQueryParameter("scope")!!.contains("offline_access"))
    }
}
