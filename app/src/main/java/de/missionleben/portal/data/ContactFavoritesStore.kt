package de.missionleben.portal.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Only lookup references are retained. Names/phone numbers shown in the UI are fetched anew. */
data class ContactFavorite(val id: String, val query: String) {
    companion object {
        fun from(contact: EmployeeContact) = ContactFavorite(contact.id,
            contact.email.ifBlank { contact.name }.trim().take(100))
    }
}

/** Personal-device preferences, encrypted and bound to both device and signed-in subject. */
class ContactFavoritesStore(
    context: Context,
    private val key: () -> SecretKey = { keystoreKey() },
) {
    private val preferences = context.getSharedPreferences("mission_leben_contact_favorites", Context.MODE_PRIVATE)

    fun read(subject: String, deviceId: String): List<ContactFavorite> {
        val scope = scope(subject, deviceId) ?: return emptyList()
        val envelope = preferences.getString(scope, null) ?: return emptyList()
        return runCatching {
            val root = JSONObject(envelope)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, decode(root.getString("iv"))))
            cipher.updateAAD(scope.toByteArray(Charsets.UTF_8))
            val values = JSONArray(cipher.doFinal(decode(root.getString("data"))).toString(Charsets.UTF_8))
            require(values.length() <= MAX_FAVORITES)
            List(values.length()) { index ->
                val value = values.getJSONObject(index)
                ContactFavorite(value.getString("id"), value.getString("query"))
                    .also { require(it.id.isNotBlank() && it.id.length <= 128 && it.query.length in 1..100) }
            }.distinctBy { it.id }
        }.getOrDefault(emptyList())
    }

    fun write(subject: String, deviceId: String, values: List<ContactFavorite>) {
        val scope = scope(subject, deviceId) ?: return
        require(values.size <= MAX_FAVORITES)
        val items = JSONArray()
        values.distinctBy { it.id }.forEach {
            require(it.id.isNotBlank() && it.id.length <= 128 && it.query.length in 1..100)
            items.put(JSONObject().put("id", it.id).put("query", it.query))
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(scope.toByteArray(Charsets.UTF_8))
        val encrypted = cipher.doFinal(items.toString().toByteArray(Charsets.UTF_8))
        preferences.edit().putString(scope, JSONObject().put("iv", encode(cipher.iv))
            .put("data", encode(encrypted)).toString()).apply()
    }

    fun clearAll() { preferences.edit().clear().apply() }

    private fun scope(subject: String, deviceId: String): String? {
        if (subject.isBlank() || deviceId.isBlank()) return null
        val value = "${deviceId.length}:$deviceId:$subject"
        return MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun encode(value: ByteArray) = Base64.encodeToString(value, Base64.NO_WRAP)
    private fun decode(value: String) = Base64.decode(value, Base64.NO_WRAP)

    companion object {
        const val MAX_FAVORITES = 20

        private fun keystoreKey(): SecretKey {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val alias = "ml-contact-favorites-v1"
            (store.getKey(alias, null) as? SecretKey)?.let { return it }
            return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            }.generateKey()
        }
    }
}
