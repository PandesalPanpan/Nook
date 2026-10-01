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
class PortabilityTest {
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), NookDatabase::class.java).build()
    private val repo = NookRepository(db, "local:source", "android", { 100L })
    @After fun close() = db.close()

    @Test fun originalBytesSurviveProcessingBackupAndAccountMerge() = runTest {
        val original = ByteArray(3 * 1024 * 1024) { (it % 251).toByte() }
        val capture = repo.capture("Photo note", "image", listOf(OriginalInput("photo.jpg", "image/jpeg", original)))
        val note = repo.process(capture.id, "note")
        val backup = repo.exportBackup()
        val restored = NookRepository(db, "local:restored", "other")
        assertEquals(3, restored.restoreBackup(backup))
        assertEquals(0, restored.restoreBackup(backup))
        val attachmentId = db.records().all(restored.accountId).map { it.decode() }.single { it.kind == "attachment" }.id
        val attachment = wireJson.decodeFromJsonElement(Attachment.serializer(), restored.get(attachmentId)!!.data)
        assertEquals(note.id, attachment.ownerId)
        assertArrayEquals(original, db.records().original(restored.accountId, attachmentId)!!.bytes)
        val synced = restored.mergeIntoAccount("cloud-user")
        assertArrayEquals(original, db.records().original(synced.accountId, attachmentId)!!.bytes)
        assertNull(db.records().original(restored.accountId, attachmentId))
    }

    @Test fun restoringOlderBackupNeverResurrectsDeletedNote() = runTest {
        val capture = repo.capture("Keep deletion")
        val note = repo.process(capture.id, "note")
        val backup = repo.exportBackup()
        repo.delete(note.id)
        repo.restoreBackup(backup)
        assertTrue(repo.get(note.id)!!.deleted)
    }
    @Test fun deletingOwnerPermanentlyDeletesOriginalFiles() = runTest {
        val capture = repo.capture("", "image", listOf(OriginalInput("photo.png", "image/png", byteArrayOf(1, 2, 3))))
        val note = repo.process(capture.id, "note")
        val attachment = db.records().all(repo.accountId).map { it.decode() }.single { it.kind == "attachment" }
        val backup = repo.exportBackup()
        repo.delete(note.id)
        assertTrue(repo.get(attachment.id)!!.deleted)
        assertNull(db.records().original(repo.accountId, attachment.id))
        repo.restoreBackup(backup)
        assertTrue(repo.get(note.id)!!.deleted)
        assertTrue(repo.get(attachment.id)!!.deleted)
        assertNull(db.records().original(repo.accountId, attachment.id))
    }
    @Test fun existingNoteAttachmentsCommitAtomicallyAndRejectDeletedOwners() = runTest {
        val note = repo.create("note", kotlinx.serialization.json.JsonObject(mapOf("title" to kotlinx.serialization.json.JsonPrimitive("Files"), "body" to kotlinx.serialization.json.JsonPrimitive("Text"), "attachmentIds" to kotlinx.serialization.json.JsonArray(emptyList()))))
        val original = OriginalInput("proof.bin", "application/octet-stream", byteArrayOf(1, 2, 3))
        repo.attach(note.id, listOf(original))
        val saved = wireJson.decodeFromJsonElement(Note.serializer(), repo.get(note.id)!!.data)
        assertEquals(1, saved.attachmentIds.size)
        assertArrayEquals(original.bytes, db.records().original(repo.accountId, saved.attachmentIds.single())!!.bytes)
        repo.delete(note.id)
        try {repo.attach(note.id, listOf(original)); fail("Deleted owner must reject attachment")} catch(_: IllegalArgumentException) { }
        assertNull(db.records().original(repo.accountId, saved.attachmentIds.single()))
    }
}
