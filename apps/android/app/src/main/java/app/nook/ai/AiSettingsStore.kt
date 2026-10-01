package app.nook.ai

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import app.nook.data.wireJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Account-scoped, encrypted device preferences; never Room, ZIP or Firebase. */
class AiSettingsStore(context: Context, private val accountId: String) {
    private val preferences = context.applicationContext.getSharedPreferences("nook-ai-private", Context.MODE_PRIVATE)
    private fun key(): SecretKey = synchronized(lock) {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("nook-ai-v1", null) as? SecretKey) ?: KeyGenerator.getInstance("AES", "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder("nook-ai-v1", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build()); generateKey()
        }
    }
    suspend fun load(): AiConfiguration = withContext(Dispatchers.IO) {
        val stored = preferences.getString(accountId, null) ?: return@withContext AiConfiguration()
        try {
            val bytes = Base64.decode(stored, Base64.NO_WRAP); require(bytes.size > 12)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0,12))); cipher.updateAAD(accountId.toByteArray())
            validateAiConfiguration(wireJson.decodeFromString<AiConfiguration>(cipher.doFinal(bytes.copyOfRange(12,bytes.size)).toString(Charsets.UTF_8)))
        } catch (_: Exception) { error("AI settings could not be read. Save your provider again.") }
    }
    suspend fun save(configuration: AiConfiguration) = withContext(Dispatchers.IO) {
        val config = validateAiConfiguration(configuration)
        if(config.provider == "off") { check(preferences.edit().remove(accountId).commit()); return@withContext }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key()); cipher.updateAAD(accountId.toByteArray())
        val encrypted = cipher.iv + cipher.doFinal(wireJson.encodeToString(config).toByteArray())
        check(preferences.edit().putString(accountId, Base64.encodeToString(encrypted, Base64.NO_WRAP)).commit()) { "AI settings could not be saved" }
    }
    companion object { private val lock = Any() }
}
