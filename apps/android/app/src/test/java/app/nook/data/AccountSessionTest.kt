package app.nook.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
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
class AccountSessionTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, NookDatabase::class.java).build()
    private val guest = NookRepository(db, "local:device", "client")
    private val transport = object : SyncTransport {
        override suspend fun push(record: Record) = record
        override suspend fun pull(accountId: String) = emptyList<Record>()
    }
    @After fun close() { db.close() }
    @Test fun activeNamespaceIsCommittedBeforePublishingTheNewRepository() = runTest {
        val committed = mutableListOf<String>()
        lateinit var session: AccountSession
        session = AccountSession(guest, activated = { next ->
            assertTrue(session.state.value.changing)
            committed.add(next.accountId)
        }) { transport }
        session.activate("alice")
        assertEquals(listOf("alice"), committed)
        assertEquals("alice", session.state.value.repository.accountId)
        session.activate(null)
        assertEquals(listOf("alice", guest.accountId), committed)
        assertFalse(session.state.value.changing)
    }
    @Test fun explicitGuestMergeAndCloudSwitchPreserveIsolatedNamespaces() = runTest {
        val session = AccountSession(guest) { transport }
        val thought = guest.capture("Guest Alice")
        session.activate("alice", mergeGuest = true)
        assertNotNull(session.state.value.repository.get(thought.id))
        assertNull(guest.get(thought.id))
        session.prepareSignOut(); session.activate(null)
        val local = guest.capture("Separate local")
        session.activate("alice")
        session.activate("bob", mergeGuest = true)
        assertNull(session.state.value.repository.get(thought.id))
        assertNull(session.state.value.repository.get(local.id))
        assertNotNull(guest.get(local.id))
        session.activate("alice")
        assertNotNull(session.state.value.repository.get(thought.id))
    }
    @Test fun invalidAdapterDoesNotMoveGuestDataAndCanRecover() = runTest {
        val thought = guest.capture("Keep local")
        val session = AccountSession(guest) { account -> check(account != "bad") { "Configuration missing" }; transport }
        try { session.activate("bad", mergeGuest = true); fail("Expected invalid configuration") }
        catch (_: IllegalStateException) { }
        assertNotNull(guest.get(thought.id))
        assertEquals("Configuration missing", session.state.value.error)
        session.activate("alice", mergeGuest = true)
        assertNull(session.state.value.error)
        assertNotNull(session.state.value.repository.get(thought.id))
    }
    @Test fun signOutStopsUnresolvedSyncAndKeepsCloudOutbox() = runTest {
        val started = CompletableDeferred<Unit>()
        val never = CompletableDeferred<Record>()
        val session = AccountSession(guest) { object : SyncTransport {
            override suspend fun push(record: Record): Record { started.complete(Unit); return never.await() }
            override suspend fun pull(accountId: String) = emptyList<Record>()
        } }
        session.activate("alice")
        val alice = session.state.value.repository
        val thought = alice.capture("Pending")
        val syncing = launch { session.sync() }; started.await()
        withTimeout(1000) { session.prepareSignOut(); session.activate(null); syncing.join() }
        assertEquals(guest.accountId, session.state.value.repository.accountId)
        assertNotNull(db.records().operation("alice", thought.id))
        assertNull(guest.get(thought.id))
    }
    @Test fun fileFailureAfterMetadataAckPublishesSafeRetryAndClearsOnRecoveryAndSignOut() = kotlinx.coroutines.runBlocking {
        var remote = emptyList<Record>(); var fail = true
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val session = AccountSession(guest) { object : SyncTransport {
            override suspend fun push(record: Record) = record
            override suspend fun pull(accountId: String) = remote
            override val originals = object : OriginalTransport {
                override suspend fun upload(record: Record, bytes: ByteArray) {
                    entered.complete(Unit); release.await(); if(fail) error("private transport detail")
                }
                override suspend fun download(record: Record) = byteArrayOf(1)
                override suspend fun remove(record: Record) {}
            }
        } }
        session.activate("alice"); val repo = session.state.value.repository
        val capture = repo.create("capture", wireJson.encodeToJsonElement(Capture("Photo", "image")) as JsonObject)
        val file = repo.create("attachment", wireJson.encodeToJsonElement(Attachment("photo.png", "image/png", 1, capture.id)) as JsonObject)
        db.records().putOriginal(OriginalFile("alice", file.id, byteArrayOf(1))); remote = listOf(capture, file)
        val job = launch { assertTrue(runCatching { session.sync() }.isFailure) }
        withTimeout(5000) { entered.await() }
        assertTrue(session.state.value.syncing)
        assertTrue(db.records().due("alice", Long.MAX_VALUE).isEmpty())
        assertEquals("photo.png", session.state.value.files?.filename)
        assertEquals(FileSyncPhase.UPLOADING, session.state.value.files?.phase)
        release.complete(Unit); job.join()
        assertFalse(session.state.value.syncing)
        assertTrue(session.state.value.syncError!!.contains("files could not sync"))
        assertFalse(session.state.value.syncError!!.contains("private transport detail"))
        assertEquals(FileSyncProgress(1,1,1), session.state.value.files)
        fail = false; session.sync()
        assertNull(session.state.value.syncError); assertNotNull(session.state.value.lastSyncAt)
        session.prepareSignOut()
        assertFalse(session.state.value.syncing); assertNull(session.state.value.lastSyncAt)
        session.activate("bob"); assertNull(session.state.value.syncError); assertNull(session.state.value.files)
    }

    @Test fun overlappingAttemptsKeepProgressActiveUntilEveryCallFinishes() = kotlinx.coroutines.runBlocking {
        val firstEntered = CompletableDeferred<Unit>(); val secondEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>(); val releaseSecond = CompletableDeferred<Unit>()
        var count = 0
        val session = AccountSession(guest) { object : SyncTransport {
            override suspend fun push(record: Record) = record
            override suspend fun pull(accountId: String): List<Record> {
                if(++count == 1) { firstEntered.complete(Unit); releaseFirst.await() }
                else { secondEntered.complete(Unit); releaseSecond.await() }
                return emptyList()
            }
        } }
        session.activate("alice")
        val first = launch { session.sync() }
        withTimeout(5000) { firstEntered.await() }
        val second = launch { session.sync() }
        kotlinx.coroutines.yield()
        assertTrue(session.state.value.syncing)
        releaseFirst.complete(Unit)
        withTimeout(5000) { secondEntered.await() }
        first.join(); assertTrue(session.state.value.syncing)
        releaseSecond.complete(Unit); second.join()
        assertFalse(session.state.value.syncing)
    }

}
