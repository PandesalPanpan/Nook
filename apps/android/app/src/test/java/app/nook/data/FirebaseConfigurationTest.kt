package app.nook.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FirebaseConfigurationTest {
    @Test fun absentConfigurationKeepsDefaultInstallationLocalOnly() {
        assertNull(loadFirebaseConfiguration(ApplicationProvider.getApplicationContext<Context>()))
    }
    @Test fun demoRoutingAndRequiredPublicFieldsAreValidated() {
        try { FirebaseConfiguration("key", "app", "demo-nook").validateConfiguration(); fail("Expected emulator requirement") }
        catch (_: IllegalArgumentException) { }
        try { FirebaseConfiguration("", "app", "nook").validateConfiguration(); fail("Expected required API key") }
        catch (_: IllegalArgumentException) { }
        val config = FirebaseConfiguration("demo-nook", "demo-nook", "demo-nook", emulatorHost = "10.0.2.2")
        assertEquals("demo-nook", config.options().projectId)
    }
}
