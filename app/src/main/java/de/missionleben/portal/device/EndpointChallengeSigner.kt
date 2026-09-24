package de.missionleben.portal.device

import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object EndpointChallengeSigner {
    fun sign(
        challenge: String,
        credential: AuthentikDeviceCredential,
    ): String {
        require(challenge.length in 32..8192 && challenge.count { it == '.' } == 2) {
            "Invalid Authentik endpoint challenge"
        }
        val header = "{\"alg\":\"HS512\",\"typ\":\"JWT\"}"
        val payload = "{" +
            "\"iss\":${jsonString(credential.identifier)}," +
            "\"atc\":${jsonString(challenge)}," +
            "\"aud\":\"goauthentik.io/platform/endpoint\"" +
            "}"
        val unsigned = "${base64Url(header.toByteArray())}.${base64Url(payload.toByteArray())}"
        val mac = Mac.getInstance("HmacSHA512")
        mac.init(SecretKeySpec(credential.token.toByteArray(Charsets.UTF_8), "HmacSHA512"))
        return "$unsigned.${base64Url(mac.doFinal(unsigned.toByteArray(Charsets.UTF_8)))}"
    }

    private fun base64Url(value: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(value)

    private fun jsonString(value: String): String = buildString {
        append('"')
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character.code < 0x20) {
                    append("\\u%04x".format(character.code))
                } else {
                    append(character)
                }
            }
        }
        append('"')
    }
}
