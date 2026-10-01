package app.nook

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import app.nook.data.*
import com.google.firebase.FirebaseOptions
import com.google.firebase.storage.StorageException
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.UUID

class FirebaseTransportTest {
    @Test fun nativeAuthMergeUpdatesAndPermanentDeletionUseActualEmulatorRules() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("firebaseEmulators") == "true")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val services = initializeNookFirebase(context, FirebaseOptions.Builder().setApiKey("demo-nook")
            .setApplicationId("1:123456789:android:demo-nook").setProjectId("demo-nook").setStorageBucket("demo-nook.appspot.com").build(), "10.0.2.2")
        val a = Room.inMemoryDatabaseBuilder(context, NookDatabase::class.java).build()
        val b = Room.inMemoryDatabaseBuilder(context, NookDatabase::class.java).build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val guest = NookRepository(a, "local:emulator", "android-a")
        val accounts = AccountSession(guest) { uid -> FirebaseTransport(services.firestore, uid) }
        val auth = AuthSession(accounts, FirebaseAuthPort(services.auth), scope)
        try {
            withTimeout(30_000) {
                val captured = guest.capture("Native guest before sign-in")
                val aliceEmail = "native-${UUID.randomUUID()}@example.com"
                auth.signIn(aliceEmail, "test-only-password", createAccount = true)
                val alice = accounts.state.value.repository
                assertEquals(services.auth.currentUser!!.uid, alice.accountId)
                val other = NookRepository(b, alice.accountId, "android-b")
                val transport = FirebaseTransport(services.firestore, alice.accountId)
                accounts.sync(); SyncEngine(other, transport).sync()
                assertEquals(captured.id, other.get(captured.id)!!.id)
                alice.archive(captured.id, true)
                accounts.sync(); SyncEngine(other, transport).sync()
                assertTrue(other.get(captured.id)!!.archived)
                alice.delete(captured.id); accounts.sync()
                other.archive(captured.id, false)
                SyncEngine(other, transport).sync()
                assertTrue(other.get(captured.id)!!.deleted)
                val originalBytes = ByteArray(128 * 1024) { (it % 251).toByte() }
                val fileCapture = alice.capture("Private original", "image", listOf(OriginalInput("../original.txt", "text/plain", originalBytes)))
                val attachmentId = alice.db.records().all(alice.accountId).map { it.decode() }.first { it.kind == "attachment" }.id
                val progress = java.util.concurrent.ConcurrentLinkedQueue<FileSyncProgress>()
                SyncEngine(alice, transport).observeProgress { progress.add(it) }.sync()
                assertTrue(progress.any { it.phase == FileSyncPhase.UPLOADING && (it.transferred ?: 0) > 0 })
                assertEquals(FileSyncProgress(1,1), progress.last())
                progress.clear()
                SyncEngine(other, transport).observeProgress { progress.add(it) }.sync()
                assertTrue(progress.any { it.phase == FileSyncPhase.DOWNLOADING })
                assertEquals(FileSyncProgress(1,1), progress.last())
                assertArrayEquals(originalBytes, other.db.records().original(alice.accountId, attachmentId)!!.bytes)
                assertEquals(alice.db.records().originalRevision(alice.accountId, attachmentId), alice.db.records().transfer(alice.accountId, attachmentId)!!.revision)
                assertEquals(other.db.records().originalRevision(alice.accountId, attachmentId), other.db.records().transfer(alice.accountId, attachmentId)!!.revision)
                assertEquals(transport.originals.cacheKey, other.db.records().transfer(alice.accountId, attachmentId)!!.scope)
                val original = services.storage.reference.child("users/${alice.accountId}/attachments/$attachmentId/original")
                assertArrayEquals(originalBytes, original.getBytes(256L * 1024).await())
                auth.signIn("native-bob-${UUID.randomUUID()}@example.com", "test-only-password", createAccount = true)
                assertNotEquals(alice.accountId, accounts.state.value.repository.accountId)
                assertNull(accounts.state.value.repository.get(captured.id))
                try { original.getBytes(10).await(); fail("Another account must not read Alice's original") }
                catch (denied: StorageException) { assertEquals(StorageException.ERROR_NOT_AUTHORIZED, denied.errorCode) }
                auth.signIn(aliceEmail, "test-only-password")
                alice.delete(fileCapture.id); accounts.sync(); SyncEngine(other, transport).sync()
                assertNull(other.db.records().original(alice.accountId, attachmentId))
                auth.signOut()
                assertNull(services.auth.currentUser)
                assertEquals(guest.accountId, accounts.state.value.repository.accountId)
                assertNull(guest.get(captured.id))
            }
        } finally { auth.dispose(); scope.cancel(); a.close(); b.close(); services.app.delete() }
    }
}
