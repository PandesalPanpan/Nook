package app.nook.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class AuthSessionTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, NookDatabase::class.java).build()
    private val guest = NookRepository(db, "local:auth", "client")
    private val accounts = AccountSession(guest) { object : SyncTransport {
        override suspend fun push(record: Record) = record
        override suspend fun pull(accountId: String) = emptyList<Record>()
    } }
    private class FakeAuth : AuthPort {
        var identity: AuthIdentity? = null
        var fail = false
        var onChange: () -> Unit = {}
        val listeners = mutableSetOf<() -> Unit>()
        override fun current() = identity
        override fun observe(listener: () -> Unit): () -> Unit { listeners.add(listener); return { listeners.remove(listener) } }
        private fun change(uid: String?) {
            onChange()
            check(!fail) { "Auth unavailable" }
            identity = uid?.let { AuthIdentity(it, "$it@example.com") }
            listeners.toList().forEach { it() }
        }
        override suspend fun signIn(email: String, password: String) { change(email.substringBefore('@')) }
        override suspend fun register(email: String, password: String) { signIn(email, password) }
        override suspend fun google(idToken: String) { change("google-user") }
        override suspend fun signOut() { change(null) }
    }
    @After fun close() { db.close() }
    @Test fun googlePickerDismissalPreservesTheCurrentSyncAndGuestData() = runTest {
        val port = FakeAuth().apply { identity = AuthIdentity("alice", "alice@example.com") }
        var cancelledWorkers = 0
        val auth = AuthSession(accounts, port, backgroundScope, beforeCredentialChange = { cancelledWorkers++ })
        auth.initialize()
        val record = accounts.state.value.repository.capture("Pending Alice")
        try { auth.signInWithGoogle { error("Picker dismissed") }; fail("Expected dismissed picker") } catch (_: IllegalStateException) { }
        assertEquals(0, cancelledWorkers)
        assertEquals("alice", auth.identity()!!.uid)
        accounts.sync(); assertNull(db.records().operation("alice", record.id))
        auth.dispose()
    }
    @Test fun googleSignInUsesTheSameSafeExplicitGuestMerge() = runTest {
        val guestRecord = guest.capture("Guest before Google")
        val port = FakeAuth()
        val auth = AuthSession(accounts, port, backgroundScope)
        auth.signInWithGoogle { "test-only-token" }
        assertEquals("google-user", accounts.state.value.repository.accountId)
        assertNotNull(accounts.state.value.repository.get(guestRecord.id)); assertNull(guest.get(guestRecord.id))
        auth.signOut(); assertEquals(guest.accountId, accounts.state.value.repository.accountId)
        auth.dispose()
    }
    @Test fun backgroundWorkCancellationPrecedesCredentialChanges() = runTest {
        val events = mutableListOf<String>()
        val port = FakeAuth().apply { onChange = { events.add("credentials") } }
        val auth = AuthSession(accounts, port, backgroundScope, beforeCredentialChange = { events.add("cancel workers") })
        auth.signIn("alice@example.com", "test-only")
        auth.signOut()
        assertEquals(listOf("cancel workers", "credentials", "cancel workers", "credentials"), events)
        auth.dispose()
    }
    @Test fun failedWorkerCancellationKeepsIdentityAndResumesPendingSync() = runTest {
        val port = FakeAuth().apply { identity = AuthIdentity("alice", "alice@example.com") }
        val auth = AuthSession(accounts, port, backgroundScope, beforeCredentialChange = { error("Worker cancellation failed") })
        auth.initialize()
        val thought = accounts.state.value.repository.capture("Queued")
        try { auth.signOut(); fail("Expected failed barrier") } catch (_: IllegalStateException) { }
        assertEquals("alice", port.current()!!.uid)
        accounts.sync()
        assertNull(db.records().operation("alice", thought.id))
        auth.dispose()
    }
    @Test fun unchangedAuthCallbackDoesNotResetTheActiveRepository() = runTest {
        val port = FakeAuth().apply { identity = AuthIdentity("alice", "alice@example.com") }
        val auth = AuthSession(accounts, port, backgroundScope)
        auth.initialize()
        val repository = accounts.state.value.repository
        port.listeners.toList().forEach { it() }
        kotlinx.coroutines.yield()
        assertSame(repository, accounts.state.value.repository)
        auth.dispose()
    }
    @Test fun restoredAuthenticationDoesNotImportGuestData() = runTest {
        val thought = guest.capture("Private guest")
        val port = FakeAuth().apply { identity = AuthIdentity("alice", "alice@example.com") }
        val auth = AuthSession(accounts, port, backgroundScope)
        auth.initialize()
        assertEquals("alice", accounts.state.value.repository.accountId)
        assertNull(accounts.state.value.repository.get(thought.id)); assertNotNull(guest.get(thought.id))
        auth.dispose(); assertTrue(port.listeners.isEmpty())
    }
    @Test fun registrationMergesAndSignOutSeparatesLocalAndCloudAccounts() = runTest {
        val thought = guest.capture("Before account")
        val port = FakeAuth(); val auth = AuthSession(accounts, port, backgroundScope)
        auth.signIn("alice@example.com", "test-only", createAccount = true)
        assertNotNull(accounts.state.value.repository.get(thought.id)); assertNull(guest.get(thought.id))
        auth.signOut(); assertEquals(guest.accountId, accounts.state.value.repository.accountId)
        val local = guest.capture("New local")
        auth.signIn("alice@example.com", "test-only")
        auth.signIn("bob@example.com", "test-only", createAccount = true)
        assertNull(accounts.state.value.repository.get(thought.id)); assertNull(accounts.state.value.repository.get(local.id))
        auth.signIn("alice@example.com", "test-only")
        assertNotNull(accounts.state.value.repository.get(thought.id)); auth.dispose()
    }
    @Test fun failedSignOutResumesAccountAndRetainsPendingWork() = runTest {
        val port = FakeAuth(); val auth = AuthSession(accounts, port, backgroundScope)
        auth.signIn("alice@example.com", "test-only")
        val thought = accounts.state.value.repository.capture("Queued")
        port.fail = true
        try { auth.signOut(); fail("Expected failed sign-out") } catch (_: IllegalStateException) { }
        assertEquals("alice", accounts.state.value.repository.accountId)
        accounts.sync(); assertNull(db.records().operation("alice", thought.id)); auth.dispose()
    }
}
