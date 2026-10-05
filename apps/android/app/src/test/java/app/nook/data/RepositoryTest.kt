package app.nook.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
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
import org.json.JSONObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.JsonObject

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class RepositoryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, NookDatabase::class.java).build()
    private val repo = NookRepository(db, "local:test", "android", { 100L })
    @After fun close() { db.close() }
    @Test fun captureProcessingKeepsHistoryAndReusesTheSameDestination() = runTest {
        val capture = repo.capture("Read offline")
        val note = repo.process(capture.id, "note")
        assertEquals("note", note.kind)
        val history = repo.get(capture.id)!!
        val captureData = wireJson.decodeFromJsonElement(Capture.serializer(), history.data)
        assertFalse(history.deleted)
        assertEquals(2, history.schemaVersion)
        assertEquals("Read offline", captureData.originalBody)
        assertEquals(listOf(note.id), captureData.processedIds)
        assertNotNull(captureData.processedAt)
        assertEquals(2, db.records().due(repo.accountId, 1000).size)
        assertEquals(note.id, repo.process(capture.id, "task").id)
        assertEquals(2, db.records().all(repo.accountId).size)
    }
    @Test fun splitKeepsAttachmentsAndConnectsTaskAndNoteUnderAResource() = runTest {
        val bytes = byteArrayOf(1, 2, 3, 4)
        val capture = repo.capture("Original thought", "image", listOf(OriginalInput("room.jpg", "image/jpeg", bytes)))
        val captureData = wireJson.decodeFromJsonElement(Capture.serializer(), capture.data)
        val resource = repo.create("resource", wireJson.encodeToJsonElement(Resource("Bedroom")) as JsonObject)
        repo.saveClarificationDraft(capture.id, "Edited thought", ClarificationDraft("split", "Replace the bulb", "Lighting", "Warm light", resource.id))
        val results = repo.clarify(capture.id, "Edited thought", "split", "Replace the bulb", "Lighting", "Warm light", resource.id)
        val note = results.first { it.kind == "note" }
        val task = results.first { it.kind == "task" }
        val noteData = wireJson.decodeFromJsonElement(Note.serializer(), note.data)
        val taskData = wireJson.decodeFromJsonElement(Task.serializer(), task.data)
        assertEquals(listOf(captureData.attachmentIds.single()), noteData.attachmentIds)
        assertEquals(resource.id, noteData.resourceId)
        assertEquals(resource.id, taskData.resourceId)
        assertEquals(listOf(task.id), noteData.relatedIds)
        assertEquals(listOf(note.id), taskData.relatedIds)
        assertEquals(note.id, wireJson.decodeFromJsonElement(Attachment.serializer(), requireNotNull(repo.get(captureData.attachmentIds.single())).data).ownerId)
        assertArrayEquals(bytes, db.records().original(repo.accountId, captureData.attachmentIds.single())!!.bytes)
        assertEquals(listOf(note.id, task.id), repo.clarify(capture.id, "ignored", "task").map { it.id })
        assertEquals(1, db.records().byKinds(repo.accountId, listOf("note")).count { it.decode().data["sourceCaptureId"] != null })
    }
    @Test fun failedClarificationRollsBackAndADeletedResultIsNeverRecreated() = runTest {
        val capture = repo.capture("Schedule this")
        try { repo.clarify(capture.id, "Schedule this", "task", deadline = "2026-02-30"); fail("Invalid date accepted") } catch (_: Exception) { }
        assertNull(wireJson.decodeFromJsonElement(Capture.serializer(), repo.get(capture.id)!!.data).processedAt)
        assertTrue(db.records().byKinds(repo.accountId, listOf("task")).isEmpty())
        val task = repo.clarify(capture.id, "Schedule this", "task").single()
        repo.delete(task.id)
        try { repo.clarify(capture.id, "Schedule this", "task"); fail("Deleted result was recreated") } catch (_: IllegalStateException) { }
        assertTrue(repo.get(task.id)!!.deleted)
    }
    @Test fun tombstoneDefeatsLaterClockEdit() = runTest {
        val capture = repo.capture("Private")
        repo.delete(capture.id)
        repo.receive(capture.copy(updatedAt = 100000))
        assertTrue(repo.get(capture.id)!!.deleted)
    }
    @Test fun guestMigrationIsIdempotentAndAccountsAreIsolated() = runTest {
        val capture = repo.capture("Guest")
        val alice = repo.mergeIntoAccount("alice")
        repo.mergeIntoAccount("alice")
        assertNull(repo.get(capture.id))
        assertEquals(capture.id, alice.get(capture.id)!!.id)
        assertEquals(1, db.records().due("alice", 1000).size)
        val bob = NookRepository(db, "bob", "android")
        bob.receive(capture.copy(accountId = "bob"))
        assertEquals("alice", alice.get(capture.id)!!.accountId)
        assertEquals("bob", bob.get(capture.id)!!.accountId)
    }
    @Test fun archiveRestoresWithoutDeleting() = runTest {
        val capture = repo.capture("Archive")
        repo.archive(capture.id, true); repo.archive(capture.id, false)
        assertFalse(repo.get(capture.id)!!.archived)
        assertFalse(repo.get(capture.id)!!.deleted)
    }
    @Test fun diskDatabaseSurvivesReopening() = runTest {
        val name = "nook-test-${java.util.UUID.randomUUID()}.db"
        val first = Room.databaseBuilder(context, NookDatabase::class.java, name).build()
        val repository = NookRepository(first, "local:disk", "android")
        val capture = repository.capture("Saved on disk")
        first.close()
        val reopened = Room.databaseBuilder(context, NookDatabase::class.java, name).build()
        try {
            val again = NookRepository(reopened, "local:disk", "android")
            assertEquals(capture, again.get(capture.id))
            assertNotNull(reopened.records().operation("local:disk", capture.id))
        } finally { reopened.close(); context.deleteDatabase(name) }
    }
    @Test fun fullTextSearchUpdatesWithProcessingEditsAndDeletion() = runTest {
        val capture = repo.capture("Garden planning")
        assertEquals(1, db.records().searchableRecords(repo.accountId).size)
        assertEquals(1, db.records().search(repo.accountId, "garden").size)
        assertEquals(capture.id, repo.search("gard plan").single().id)
        val note = repo.process(capture.id, "note")
        assertEquals(setOf(note.id, capture.id), repo.search("garden").map { it.id }.toSet())
        repo.archive(note.id, true)
        assertEquals(capture.id, repo.search("garden", archived = false).single().id)
        assertEquals(note.id, repo.search("garden", archived = true).single().id)
        repo.delete(note.id)
        assertEquals(capture.id, repo.search("garden").single().id)
    }
    @Test fun migrationRetainsRecordsAndBuildsFullTextIndex() = runTest {
        val name = "migration-${java.util.UUID.randomUUID()}.db"
        val schemaText = requireNotNull(javaClass.classLoader?.getResourceAsStream("app.nook.data.NookDatabase/1.json")).bufferedReader().use { it.readText() }
        val schema = JSONObject(schemaText).getJSONObject("database")
        val path = context.getDatabasePath(name); path.parentFile!!.mkdirs()
        val legacy = SQLiteDatabase.openOrCreateDatabase(path, null)
        val entities = schema.getJSONArray("entities")
        for (index in 0 until entities.length()) {
            val entity = entities.getJSONObject(index); val tableName = entity.getString("tableName")
            legacy.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", tableName))
            val indexes = entity.getJSONArray("indices")
            for (i in 0 until indexes.length()) legacy.execSQL(indexes.getJSONObject(i).getString("createSql").replace("\${TABLE_NAME}", tableName))
        }
        val setup = schema.getJSONArray("setupQueries")
        for (index in 0 until setup.length()) legacy.execSQL(setup.getString(index))
        val record = Record("legacy-note", "local:test", "note", wireJson.encodeToJsonElement(Note(body = "Preserved garden")) as JsonObject, createdAt = 1, updatedAt = 1, clientId = "old")
        legacy.execSQL("INSERT INTO records(accountId,id,kind,deleted,updatedAt,json) VALUES (?,?,?,?,?,?)", arrayOf<Any>(record.accountId, record.id, record.kind, 0, record.updatedAt, wireJson.encodeToString(record)))
        legacy.version = 1; legacy.close()
        val upgraded = Room.databaseBuilder(context, NookDatabase::class.java, name).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).build()
        try {
            val repository = NookRepository(upgraded, record.accountId, "android")
            assertEquals(record, repository.get(record.id))
            assertEquals(record.id, repository.search("gard").single().id)
        } finally { upgraded.close(); context.deleteDatabase(name) }
    }
}
