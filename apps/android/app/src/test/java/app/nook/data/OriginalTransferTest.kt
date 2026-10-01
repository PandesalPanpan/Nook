package app.nook.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import kotlinx.serialization.json.encodeToJsonElement
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class OriginalTransferTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val databases = mutableListOf<NookDatabase>()
    private val names = mutableListOf<String>()
    private val byteReads = AtomicInteger()
    private fun database(name: String = "transfers-${UUID.randomUUID()}.db"): NookDatabase {
        names.add(name)
        return Room.databaseBuilder(context, NookDatabase::class.java, name)
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
            .setQueryCallback({ sql, _ -> if (sql.startsWith("SELECT * FROM originals")) byteReads.incrementAndGet() }, { it.run() })
            .build().also { databases.add(it) }
    }
    @After fun close() { databases.forEach { it.close() }; names.distinct().forEach { context.deleteDatabase(it) } }

    @Test fun acknowledgementSkipsBytesAfterReopenButRechecksChangesAndExpiry() = runBlocking {
        val name = "transfer-reopen-${UUID.randomUUID()}.db"
        var db = database(name)
        var repo = NookRepository(db, "alice", "client")
        val server = SyncTest.Server()
        var time = 1000L; var uploads = 0; var scope = "bucket-a"
        server.originals = object : OriginalTransport {
            override val cacheKey get() = scope
            override suspend fun upload(record: Record, bytes: ByteArray) { uploads++ }
            override suspend fun download(record: Record) = byteArrayOf()
            override suspend fun remove(record: Record) = Unit
        }
        repo.capture("Cached original", "image", listOf(OriginalInput("large.bin", "application/octet-stream", ByteArray(1024 * 1024))))
        val id = db.records().byKinds("alice", listOf("attachment")).single().id
        SyncEngine(repo, server) { time }.sync(); assertEquals(1, uploads)
        db.close(); db = database(name); repo = NookRepository(db, "alice", "client")
        byteReads.set(0)
        SyncEngine(repo, server) { time }.sync(); assertEquals(1, uploads); assertEquals(0, byteReads.get())
        db.records().putOriginal(OriginalFile("alice", id, ByteArray(1024 * 1024)))
        SyncEngine(repo, server) { time }.sync(); assertEquals(2, uploads)
        repo.update(id) { it.copy(archived = true) }
        SyncEngine(repo, server) { time }.sync(); assertEquals(3, uploads)
        time += 15 * 60 * 1000
        SyncEngine(repo, server) { time }.sync(); assertEquals(4, uploads)
        scope = "bucket-b"
        SyncEngine(repo, server) { time }.sync(); assertEquals(5, uploads)
        assertNull(db.records().transfer("bob", id))
        repo.delete(id); SyncEngine(repo, server) { time }.sync()
        assertEquals("", db.records().transfer("alice", id)!!.revision)
    }

    @Test fun failedAndReplacedUploadCannotAcknowledgeStaleBytes() = runBlocking {
        val db = database(); val repo = NookRepository(db, "alice", "client"); val server = SyncTest.Server()
        repo.capture("Race", "image", listOf(OriginalInput("a.bin", "application/octet-stream", byteArrayOf(1))))
        val id = db.records().byKinds("alice", listOf("attachment")).single().id
        var uploads = 0
        server.originals = object : OriginalTransport {
            override val cacheKey = "bucket"
            override suspend fun upload(record: Record, bytes: ByteArray) {
                uploads++
                if (uploads == 1) throw IllegalStateException("retry")
                if (uploads == 2) db.records().putOriginal(OriginalFile("alice", id, byteArrayOf(2)))
            }
            override suspend fun download(record: Record) = byteArrayOf()
            override suspend fun remove(record: Record) = Unit
        }
        try { SyncEngine(repo, server).sync(); fail("Expected retry") } catch (_: IllegalStateException) { }
        assertNull(db.records().transfer("alice", id))
        SyncEngine(repo, server).sync(); assertNull(db.records().transfer("alice", id))
        SyncEngine(repo, server).sync(); assertEquals(3, uploads); assertNotNull(db.records().transfer("alice", id))
    }

    @Test fun versionThreeMigrationPreservesOriginalBytesAndAssignsRevision() = runBlocking {
        val name = "transfer-migration-${UUID.randomUUID()}.db"; names.add(name)
        val schema = JSONObject(requireNotNull(javaClass.classLoader?.getResourceAsStream("app.nook.data.NookDatabase/3.json")).bufferedReader().use { it.readText() }).getJSONObject("database")
        val path = context.getDatabasePath(name); path.parentFile!!.mkdirs()
        val legacy = SQLiteDatabase.openOrCreateDatabase(path, null)
        val entities = schema.getJSONArray("entities")
        for (index in 0 until entities.length()) {
            val entity = entities.getJSONObject(index); val table = entity.getString("tableName")
            legacy.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
            val indexes = entity.optJSONArray("indices") ?: org.json.JSONArray()
            for (i in 0 until indexes.length()) legacy.execSQL(indexes.getJSONObject(i).getString("createSql").replace("\${TABLE_NAME}", table))
        }
        val setup = schema.getJSONArray("setupQueries")
        for (index in 0 until setup.length()) legacy.execSQL(setup.getString(index))
        legacy.execSQL("INSERT INTO originals(accountId,id,part,bytes) VALUES (?,?,?,?)", arrayOf("alice", "legacy", 0, byteArrayOf(0, -1, 1)))
        legacy.version = 3; legacy.close()
        val upgraded = database(name)
        assertArrayEquals(byteArrayOf(0, -1, 1), upgraded.records().original("alice", "legacy")!!.bytes)
        assertTrue(upgraded.records().originalRevision("alice", "legacy")!!.isNotEmpty())
        assertNull(upgraded.records().transfer("alice", "legacy"))
    }

    @Test fun downloadedCopyIsCachedAndMissingLocalBytesAreRecovered() = runBlocking {
        val a = NookRepository(database(), "alice", "a")
        val b = NookRepository(database(), "alice", "b")
        val server = SyncTest.Server(); var downloads = 0
        server.originals = object : OriginalTransport {
            override val cacheKey = "bucket"
            override suspend fun upload(record: Record, bytes: ByteArray) = Unit
            override suspend fun download(record: Record): ByteArray { downloads++; return byteArrayOf(1, 2) }
            override suspend fun remove(record: Record) = Unit
        }
        a.capture("Download", "image", listOf(OriginalInput("a.bin", "application/octet-stream", byteArrayOf(1, 2))))
        val id = a.db.records().byKinds("alice", listOf("attachment")).single().id
        SyncEngine(a, server).sync(); SyncEngine(b, server).sync(); assertEquals(1, downloads)
        byteReads.set(0); SyncEngine(b, server).sync(); assertEquals(1, downloads); assertEquals(0, byteReads.get())
        b.db.records().removeOriginal("alice", id); SyncEngine(b, server).sync(); assertEquals(2, downloads)
        assertArrayEquals(byteArrayOf(1, 2), b.db.records().original("alice", id)!!.bytes)
    }
    @Test fun progressCoversBytesDownloadCleanupFailureAndCachedChecks() = runBlocking {
        val db = database(); val repo = NookRepository(db, "alice", "client"); val server = SyncTest.Server()
        var failing = true; var uploads = 0; var downloads = 0; var removals = 0
        var late: ((Long) -> Unit)? = null
        val capture = repo.create("capture", wireJson.encodeToJsonElement(Capture("Files", "image")) as kotlinx.serialization.json.JsonObject)
        val upload = repo.create("attachment", wireJson.encodeToJsonElement(Attachment("upload.bin", "application/octet-stream", 100, capture.id)) as kotlinx.serialization.json.JsonObject)
        db.records().putOriginal(OriginalFile("alice", upload.id, ByteArray(100)))
        val download = repo.create("attachment", wireJson.encodeToJsonElement(Attachment("download.bin", "application/octet-stream", 4, capture.id)) as kotlinx.serialization.json.JsonObject)
        val deleted = repo.create("attachment", wireJson.encodeToJsonElement(Attachment("deleted.bin", "application/octet-stream", 0, capture.id)) as kotlinx.serialization.json.JsonObject)
        repo.delete(deleted.id)
        server.originals = object : OriginalTransport {
            override val cacheKey = "progress-bucket"
            override suspend fun upload(record: Record, bytes: ByteArray) {}
            override suspend fun uploadWithProgress(record: Record, bytes: ByteArray, progress: (Long) -> Unit) {
                uploads++; late = progress; for(i in 1L..100L) progress(i); if(failing) error("private failure")
            }
            override suspend fun download(record: Record): ByteArray { downloads++; return byteArrayOf(1,2,3,4) }
            override suspend fun remove(record: Record) { removals++ }
        }
        val states = mutableListOf<FileSyncProgress>()
        val engine = SyncEngine(repo, server).observeProgress { states.add(it) }
        assertTrue(runCatching { engine.sync() }.isFailure)
        assertEquals(FileSyncProgress(3,3,1), states.last())
        assertTrue(states.any { it.phase == FileSyncPhase.UPLOADING && it.transferred == 50L })
        assertEquals(20, states.count { it.phase == FileSyncPhase.UPLOADING && (it.transferred ?: 0) > 0 })
        assertTrue(states.any { it.phase == FileSyncPhase.DOWNLOADING && it.filename == "download.bin" })
        assertTrue(states.any { it.phase == FileSyncPhase.REMOVING && it.filename == "deleted.bin" })
        assertArrayEquals(byteArrayOf(1,2,3,4), db.records().original("alice", download.id)!!.bytes)
        val count = states.size; late?.invoke(90); assertEquals(count, states.size)
        assertEquals("3 of 3 files checked · 1 file needs retry", fileSyncProgressText(states.last(), false))
        failing = false; engine.sync(); assertEquals(FileSyncProgress(3,3), states.last())
        assertEquals(listOf(2,1,1), listOf(uploads,downloads,removals))
        engine.stop(); val stopped = states.size; late?.invoke(100); assertEquals(stopped, states.size)
    }

}
