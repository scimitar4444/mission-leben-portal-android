package de.missionleben.portal.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec

class DeviceIdentity {
    private val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    fun ensureKey(): ECPublicKey {
        val existing = keyStore.getCertificate(KEY_ALIAS)?.publicKey as? ECPublicKey
        if (existing != null) return existing

        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
        )
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setUserAuthenticationRequired(false)
            .build()
        generator.initialize(spec)
        return generator.generateKeyPair().public as ECPublicKey
    }

    fun keyId(): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(ensureKey().encoded)
        return digest.take(12).joinToString("") { "%02x".format(it) }
    }

    fun publicJwk(): JSONObject {
        val publicKey = ensureKey()
        return JSONObject()
            .put("kty", "EC")
            .put("crv", "P-256")
            .put("kid", keyId())
            .put("x", base64Url(unsignedFixed(publicKey.w.affineX.toByteArray(), 32)))
            .put("y", base64Url(unsignedFixed(publicKey.w.affineY.toByteArray(), 32)))
    }

    fun sign(value: String): String {
        val privateKey = keyStore.getKey(KEY_ALIAS, null)
        val signature = Signature.getInstance("SHA256withECDSA")
        signature.initSign(privateKey as java.security.PrivateKey)
        signature.update(value.toByteArray(StandardCharsets.UTF_8))
        return base64Url(signature.sign())
    }

    private fun unsignedFixed(value: ByteArray, size: Int): ByteArray {
        val unsigned = if (value.size > size && value.first() == 0.toByte()) value.copyOfRange(1, value.size) else value
        require(unsigned.size <= size) { "EC coordinate is larger than expected" }
        return ByteArray(size - unsigned.size) + unsigned
    }

    private fun base64Url(value: ByteArray): String =
        Base64.encodeToString(value, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "ml-device-identity-v1"
    }
}
