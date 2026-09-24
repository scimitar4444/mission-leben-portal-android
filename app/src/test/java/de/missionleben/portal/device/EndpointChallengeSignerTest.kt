package de.missionleben.portal.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class EndpointChallengeSignerTest {
    @Test
    fun `creates Authentik compatible HS512 endpoint response`() {
        val credential = AuthentikDeviceCredential(
            deviceId = "11111111-1111-1111-1111-111111111111",
            identifier = "ml-android-test-device",
            token = "agent-device-token",
        )
        val challenge = "signed-header.signed-challenge-payload.signed-challenge-signature"
        val response = EndpointChallengeSigner.sign(challenge, credential)
        val parts = response.split('.')

        assertEquals(3, parts.size)
        val payload = String(Base64.getUrlDecoder().decode(parts[1]))
        assertTrue(payload.contains("\"iss\":\"${credential.identifier}\""))
        assertTrue(payload.contains("\"atc\":\"$challenge\""))
        assertTrue(payload.contains("\"aud\":\"goauthentik.io/platform/endpoint\""))
        // The Authentik endpoint challenge already expires server-side. The
        // response must not depend on the Android device clock being in sync.
        assertFalse(payload.contains("\"iat\""))
        assertFalse(payload.contains("\"exp\""))

        val mac = Mac.getInstance("HmacSHA512")
        mac.init(SecretKeySpec(credential.token.toByteArray(), "HmacSHA512"))
        val expected = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(mac.doFinal("${parts[0]}.${parts[1]}".toByteArray()))
        assertEquals(expected, parts[2])
        assertTrue(String(Base64.getUrlDecoder().decode(parts[0])).contains("\"alg\":\"HS512\""))
    }
}
