package de.missionleben.portal.device

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceRequestSignatureTest {
    @Test
    fun `canonical request covers method path device key time nonce and body`() {
        val canonical = DeviceRequestSignature.canonical(
            method = "get",
            path = "/v1/notifications/01JABCDEF0123456789XYZABCD",
            deviceId = "device-1",
            keyId = "key-1",
            timestamp = "1789682400",
            nonce = "nonce-1",
        )

        assertEquals(
            listOf(
                "ML-DEVICE-V1",
                "GET",
                "/v1/notifications/01JABCDEF0123456789XYZABCD",
                "device-1",
                "key-1",
                "1789682400",
                "nonce-1",
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            ).joinToString("\n"),
            canonical,
        )
    }
}
