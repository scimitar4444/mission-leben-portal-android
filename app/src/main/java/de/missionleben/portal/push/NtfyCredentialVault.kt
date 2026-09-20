package de.missionleben.portal.push

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class NtfySubscription(
    val baseUrl: String,
    val topic: String,
    val token: String,
    val lastMessageId: String = "",
)

class NtfyCredentialVault(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    @Synchronized
    fun load(): NtfySubscription? {
        val encoded = preferences.getString(KEY_CREDENTIAL, null) ?: return null
        return runCatching {
            val packed = Base64.decode(encoded, Base64.NO_WRAP)
            require(packed.size > IV_SIZE)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                secretKey(),
                GCMParameterSpec(TAG_BITS, packed.copyOfRange(0, IV_SIZE)),
            )
            val payload = JSONObject(
                cipher.doFinal(packed.copyOfRange(IV_SIZE, packed.size)).toString(Charsets.UTF_8),
            )
            NtfySubscription(
                baseUrl = payload.getString("base_url"),
                topic = payload.getString("topic"),
                token = payload.getString("token"),
                lastMessageId = payload.optString("last_message_id"),
            )
        }.getOrNull()
    }

    @Synchronized
    fun save(value: NtfySubscription) {
        val payload = JSONObject()
            .put("base_url", value.baseUrl)
            .put("topic", value.topic)
            .put("token", value.token)
            .put("last_message_id", value.lastMessageId)
            .toString()
            .toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val packed = cipher.iv + cipher.doFinal(payload)
        preferences.edit()
            .putString(KEY_CREDENTIAL, Base64.encodeToString(packed, Base64.NO_WRAP))
            .apply()
    }

    @Synchronized
    fun rememberMessage(messageId: String) {
        if (!NtfySubscriptionPolicy.validMessageId(messageId)) return
        val current = load() ?: return
        if (current.lastMessageId != messageId) save(current.copy(lastMessageId = messageId))
    }

    @Synchronized
    fun clear() {
        preferences.edit().clear().apply()
        if (keyStore.containsAlias(KEY_ALIAS)) keyStore.deleteEntry(KEY_ALIAS)
    }

    private fun secretKey(): SecretKey {
        val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "ml-ntfy-subscription-v1"
        const val PREFERENCES = "mission_leben_ntfy_credential"
        const val KEY_CREDENTIAL = "credential"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
        const val TAG_BITS = 128
    }
}
