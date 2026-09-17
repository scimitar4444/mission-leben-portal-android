package de.missionleben.portal.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

data class UnlockedSession(
    val serializedAuthState: String,
    val dataEncryptionKey: ByteArray,
)

class SecureSessionVault(context: Context) {
    private val preferences = context.getSharedPreferences("mission_leben_secure_session", Context.MODE_PRIVATE)
    private val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    fun hasSession(): Boolean =
        preferences.contains(KEY_WRAPPED_DEK) && preferences.contains(KEY_PAYLOAD)

    fun createSealCipher(): Cipher {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateMasterKey())
        return cipher
    }

    fun createUnlockCipher(): Cipher {
        val iv = decode(preferences.getString(KEY_WRAPPED_DEK_IV, null) ?: error("No wrapped key IV"))
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateMasterKey(), GCMParameterSpec(128, iv))
        return cipher
    }

    fun sealNewSession(serializedAuthState: String, authorizedCipher: Cipher): UnlockedSession {
        val dataKey = ByteArray(32).also(SecureRandom()::nextBytes)
        val wrappedKey = authorizedCipher.doFinal(dataKey)

        preferences.edit()
            .putString(KEY_WRAPPED_DEK_IV, encode(authorizedCipher.iv))
            .putString(KEY_WRAPPED_DEK, encode(wrappedKey))
            .apply()

        reseal(serializedAuthState, dataKey)
        return UnlockedSession(serializedAuthState, dataKey)
    }

    fun unlock(authorizedCipher: Cipher): UnlockedSession {
        val wrappedKey = decode(preferences.getString(KEY_WRAPPED_DEK, null) ?: error("No wrapped key"))
        val dataKey = authorizedCipher.doFinal(wrappedKey)
        val payloadIv = decode(preferences.getString(KEY_PAYLOAD_IV, null) ?: error("No payload IV"))
        val payload = decode(preferences.getString(KEY_PAYLOAD, null) ?: error("No payload"))

        val payloadCipher = Cipher.getInstance(TRANSFORMATION)
        payloadCipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(dataKey, "AES"), GCMParameterSpec(128, payloadIv))
        return UnlockedSession(
            serializedAuthState = payloadCipher.doFinal(payload).toString(Charsets.UTF_8),
            dataEncryptionKey = dataKey,
        )
    }

    fun reseal(serializedAuthState: String, dataKey: ByteArray) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(dataKey, "AES"))
        val encrypted = cipher.doFinal(serializedAuthState.toByteArray(Charsets.UTF_8))
        preferences.edit()
            .putString(KEY_PAYLOAD_IV, encode(cipher.iv))
            .putString(KEY_PAYLOAD, encode(encrypted))
            .apply()
    }

    fun clear() {
        preferences.edit().clear().apply()
    }

    private fun getOrCreateMasterKey(): SecretKey {
        (keyStore.getKey(MASTER_KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                MASTER_KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setUserAuthenticationRequired(true)
                .setUserAuthenticationParameters(
                    0,
                    KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL,
                )
                .setInvalidatedByBiometricEnrollment(false)
                .build(),
        )
        return generator.generateKey()
    }

    private fun encode(value: ByteArray): String = Base64.encodeToString(value, Base64.NO_WRAP)
    private fun decode(value: String): ByteArray = Base64.decode(value, Base64.NO_WRAP)

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val MASTER_KEY_ALIAS = "ml-personal-session-master-v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_WRAPPED_DEK_IV = "wrapped_dek_iv"
        const val KEY_WRAPPED_DEK = "wrapped_dek"
        const val KEY_PAYLOAD_IV = "payload_iv"
        const val KEY_PAYLOAD = "payload"
    }
}
