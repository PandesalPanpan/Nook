package app.nook.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class SignedOutFenceTest {
    @Test fun userIntentSurvivesReconstructionAndIsScopedToConfiguredApplication() {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val name="auth-fence-${UUID.randomUUID()}"
        val preferences=context.getSharedPreferences(name,Context.MODE_PRIVATE)
        val first=SignedOutFence(preferences,"project:application")
        assertFalse(first.isSignedOut())
        first.signOut()
        assertTrue(SignedOutFence(context.getSharedPreferences(name,Context.MODE_PRIVATE),"project:application").isSignedOut())
        assertFalse(SignedOutFence(preferences,"other:application").isSignedOut())
        assertEquals(mapOf("signed-out:project:application" to true),preferences.all)
        SignedOutFence(preferences,"project:application").signedIn()
        assertFalse(first.isSignedOut())
        assertTrue(preferences.all.isEmpty())
    }
}
