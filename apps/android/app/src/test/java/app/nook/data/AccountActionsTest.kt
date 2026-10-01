package app.nook.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class AccountActionsTest {
    @Test fun busyActionsDoNotDuplicateRegistrationAndFailuresReleaseTheControls() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, NookDatabase::class.java).build()
        val guest = NookRepository(db, "local:controls", "client")
        val release = CompletableDeferred<Unit>()
        var registrations = 0
        val port = object : AuthPort {
            override fun current(): AuthIdentity? = null
            override fun observe(listener: () -> Unit): () -> Unit = {}
            override suspend fun signIn(email: String, password: String) { error("Offline sign-in") }
            override suspend fun register(email: String, password: String) { registrations++; release.await(); error("Offline registration") }
            override suspend fun signOut() {}
        }
        val accounts = AccountSession(guest) { error("No cloud session") }
        val auth = AuthSession(accounts, port, backgroundScope)
        val controls = AccountActions(auth, this)
        try {
            val first = requireNotNull(controls.signIn("test@example.com", "transient-password", true))
            assertTrue(controls.state.value.busy)
            assertNull(controls.signIn("test@example.com", "transient-password", true))
            release.complete(Unit); first.join()
            assertEquals(1, registrations); assertFalse(controls.state.value.busy)
            assertEquals("Offline registration", controls.state.value.error)
            requireNotNull(controls.signIn("test@example.com", "transient-password", false)).join()
            assertEquals("Offline sign-in", controls.state.value.error)
        } finally { auth.dispose(); db.close() }
    }
}
