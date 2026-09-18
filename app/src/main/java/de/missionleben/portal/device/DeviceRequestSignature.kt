package de.missionleben.portal.device

import java.security.MessageDigest

object DeviceRequestSignature {
    fun canonical(
        method: String,
        path: String,
        deviceId: String,
        keyId: String,
        timestamp: String,
        nonce: String,
        body: String = "",
    ): String = listOf(
        VERSION,
        method.uppercase(),
        path,
        deviceId,
        keyId,
        timestamp,
        nonce,
        sha256(body),
    ).joinToString("\n")

    internal fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private const val VERSION = "ML-DEVICE-V1"
}
