package app.nook.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
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
class SyncTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val databases = mutableListOf<NookDatabase>()
    private fun repo(client: String): NookRepository {
        val db = Room.inMemoryDatabaseBuilder(context, NookDatabase::class.java).build()
        databases.add(db)
        return NookRepository(db, "alice", client, { 100L })
    }
    @After fun close() { databases.forEach { it.close() } }
    class Server : SyncTransport {
        override var originals: OriginalTransport? = null
        val records = mutableMapOf<String, Record>()
        var online = true
        override suspend fun push(record: Record): Record {
            check(online) { "Offline" }
            val previous = records[record.id]
            if (previous == null || compareVersions(record, previous) > 0) records[record.id] = record
            return records.getValue(record.id)
        }
        override suspend fun pull(accountId: String): List<Record> { check(online); return records.values.filter { it.accountId == accountId } }
    }
    @Test fun originalsRetryAfterMetadataAcknowledgementAndDeleteAcrossDevices() = runTest {
        val a = repo("a"); val b = repo("b"); val server = Server()
        val files = mutableMapOf<String, ByteArray>(); var connected = false
        server.originals = object : OriginalTransport {
            override suspend fun upload(record: Record, bytes: ByteArray) { check(connected) { "File offline" }; files[record.id] = bytes.copyOf() }
            override suspend fun download(record: Record): ByteArray { check(connected); return files.getValue(record.id).copyOf() }
            override suspend fun remove(record: Record) { check(connected); files.remove(record.id) }
        }
        val capture = a.capture("Original", "image", listOf(OriginalInput("../photo.bin", "application/octet-stream", byteArrayOf(0,-1,1,2))))
        val id = a.db.records().all("alice").map { it.decode() }.first { it.kind == "attachment" }.id
        try { SyncEngine(a, server).sync(); fail("File should retry") } catch (_: IllegalStateException) { }
        assertNull(a.db.records().operation("alice", id))
        connected = true; SyncEngine(a, server).sync(); SyncEngine(b, server).sync()
        assertArrayEquals(byteArrayOf(0,-1,1,2), b.db.records().original("alice", id)!!.bytes)
        a.delete(capture.id); SyncEngine(a, server).sync(); SyncEngine(b, server).sync()
        assertFalse(files.containsKey(id)); assertNull(b.db.records().original("alice", id))
    }
    @Test fun lateOriginalDownloadCannotRestoreLocalDeletion() = runTest {
        val a = repo("a"); val b = repo("b"); val server = Server()
        a.capture("Original", "image", listOf(OriginalInput("a.bin", "application/octet-stream", byteArrayOf(1,2))))
        SyncEngine(a, server).sync()
        val id = a.db.records().all("alice").map { it.decode() }.first { it.kind == "attachment" }.id
        server.originals = object : OriginalTransport {
            override suspend fun upload(record: Record, bytes: ByteArray) = Unit
            override suspend fun remove(record: Record) = Unit
            override suspend fun download(record: Record): ByteArray { b.delete(id); return byteArrayOf(1,2) }
        }
        SyncEngine(b, server).sync()
        assertTrue(b.get(id)!!.deleted); assertNull(b.db.records().original("alice", id))
    }
    @Test fun captureDuringPullDefersOriginalUntilMetadataIsOnServer() = runTest {
        val repository = repo("a"); val server = Server(); var created = false; var uploads = 0
        val transport = object : SyncTransport {
            override suspend fun push(record: Record) = server.push(record)
            override suspend fun pull(accountId: String): List<Record> {
                val snapshot = server.pull(accountId)
                if (!created) { created = true; repository.capture("During sync", "image", listOf(OriginalInput("new.bin", "application/octet-stream", byteArrayOf(1,2)))) }
                return snapshot
            }
            override val originals = object : OriginalTransport {
                override suspend fun upload(record: Record, bytes: ByteArray) { uploads++ }
                override suspend fun download(record: Record) = byteArrayOf()
                override suspend fun remove(record: Record) = Unit
            }
        }
        val engine = SyncEngine(repository, transport)
        engine.sync(); assertEquals(0, uploads); assertEquals(2, repository.db.records().due("alice", Long.MAX_VALUE).size)
        engine.sync(); assertEquals(1, uploads); assertTrue(repository.db.records().due("alice", Long.MAX_VALUE).isEmpty())
    }
    @Test fun offlineRetryAndReconnect() = runTest {
        val repository = repo("a"); val server = Server(); server.online = false
        val capture = repository.capture("Offline")
        var time = 0L; val engine = SyncEngine(repository, server) { time }
        try { engine.sync(); fail("Expected disconnected pull") } catch (_: IllegalStateException) { }
        val queued = repository.db.records().operation("alice", capture.id)!!
        assertEquals(1, queued.attempts); assertEquals(1000L, queued.nextAttemptAt)
        time = 1000; server.online = true; engine.sync()
        assertNull(repository.db.records().operation("alice", capture.id))
        assertEquals(capture.id, server.records[capture.id]!!.id)
    }
    @Test fun deleteDoesNotResurrectAcrossClients() = runTest {
        val a = repo("a"); val b = repo("b"); val server = Server()
        val capture = a.capture("Delete")
        SyncEngine(a, server).sync(); SyncEngine(b, server).sync()
        a.delete(capture.id); SyncEngine(a, server).sync()
        b.update(capture.id) { it.copy(updatedAt = 100000L) }
        SyncEngine(b, server).sync()
        assertTrue(b.get(capture.id)!!.deleted); assertTrue(server.records[capture.id]!!.deleted)
    }
    @Test fun concurrentEditDuringPushRemainsQueued() = runTest {
        val repository = repo("a"); val server = Server(); val capture = repository.capture("Original")
        val transport = object : SyncTransport {
            override suspend fun push(record: Record): Record { repository.archive(capture.id, true); return server.push(record) }
            override suspend fun pull(accountId: String) = server.pull(accountId)
        }
        SyncEngine(repository, transport).sync()
        assertNotNull(repository.db.records().operation("alice", capture.id))
        SyncEngine(repository, server).sync()
        assertTrue(server.records[capture.id]!!.archived)
    }
    @Test fun stopDiscardsLateNetworkResults() = runTest {
        val repository = repo("a"); val capture = repository.capture("Local")
        val started = CompletableDeferred<Unit>(); val response = CompletableDeferred<Record>()
        val engine = SyncEngine(repository, object : SyncTransport {
            override suspend fun push(record: Record): Record { started.complete(Unit); return response.await() }
            override suspend fun pull(accountId: String) = emptyList<Record>()
        })
        val syncing = launch { engine.sync() }; started.await()
        val stopping = launch { engine.stop() }
        kotlinx.coroutines.yield()
        response.complete(capture.copy(updatedAt = 10000, archived = true))
        syncing.join(); stopping.join()
        assertFalse(repository.get(capture.id)!!.archived)
        assertNotNull(repository.db.records().operation("alice", capture.id))
    }
    @Test fun stopDoesNotWaitForAnUnresolvedPush() = runTest {
        val repository = repo("a"); val capture = repository.capture("Pending offline")
        val started = CompletableDeferred<Unit>()
        val never = CompletableDeferred<Record>()
        val engine = SyncEngine(repository, object : SyncTransport {
            override suspend fun push(record: Record): Record { started.complete(Unit); return never.await() }
            override suspend fun pull(accountId: String) = emptyList<Record>()
        })
        val syncing = launch { engine.sync() }; started.await()
        kotlinx.coroutines.withTimeout(1000) { engine.stop(); syncing.join() }
        assertEquals("Pending offline", repository.get(capture.id)!!.data["body"]!!.toString().trim('"'))
        assertNotNull(repository.db.records().operation("alice", capture.id))
    }
    @Test fun stopDoesNotWaitForAnUnresolvedPull() = runTest {
        val repository = repo("a"); val started = CompletableDeferred<Unit>()
        val never = CompletableDeferred<List<Record>>()
        val engine = SyncEngine(repository, object : SyncTransport {
            override suspend fun push(record: Record) = record
            override suspend fun pull(accountId: String): List<Record> { started.complete(Unit); return never.await() }
        })
        val syncing = launch { engine.sync() }; started.await()
        kotlinx.coroutines.withTimeout(1000) { engine.stop(); syncing.join() }
        assertFalse(never.isCompleted)
    }
}
