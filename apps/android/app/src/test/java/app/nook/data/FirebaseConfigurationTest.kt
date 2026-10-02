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
        assertNull(loadFirebaseConfigurationFromAsset(ApplicationProvider.getApplicationContext<Context>(), "missing-nook-firebase.json"))
    }
    @Test fun publicConfigurationIsValidatedWithoutRequiringAWorkspaceAsset() {
        val config = parseFirebaseConfiguration("""{"apiKey":"client","applicationId":"app-id","projectId":"nook-app-e3f48","storageBucket":"bucket","googleWebClientId":"web-client"}""")
        assertEquals("nook-app-e3f48", config.projectId)
        assertEquals("web-client", config.googleWebClientId)
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
