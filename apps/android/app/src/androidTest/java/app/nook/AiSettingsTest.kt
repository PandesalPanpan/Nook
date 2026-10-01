package app.nook

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.nook.ai.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class AiSettingsTest {
    @Test fun deviceKeystoreEncryptsKeysAndBindsCiphertextToAccount() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val alice = "ai:${UUID.randomUUID()}"; val bob = "ai:${UUID.randomUUID()}"
        val preferences = context.getSharedPreferences("nook-ai-private", Context.MODE_PRIVATE)
        val first = AiSettingsStore(context, alice); val second = AiSettingsStore(context, bob)
        try {
            first.save(aiDefaults("openai").copy(apiKey = "fake-private-device-key"))
            assertEquals("fake-private-device-key", AiSettingsStore(context, alice).load().apiKey)
            val ciphertext = preferences.getString(alice, null)!!
            assertFalse(ciphertext.contains("fake-private-device-key")); assertEquals("off", second.load().provider)
            preferences.edit().putString(bob, ciphertext).commit()
            try { second.load(); fail("Ciphertext must bind account identity") } catch(error: IllegalStateException) { assertFalse(error.message!!.contains("fake-private-device-key")) }
            first.save(AiConfiguration()); assertNull(preferences.getString(alice, null))
        } finally { preferences.edit().remove(alice).remove(bob).commit() }
    }
}
