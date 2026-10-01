package app.nook.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
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
class DailyNotesTest {
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), NookDatabase::class.java).build()
    private val repo = NookRepository(db, "local:daily", "android", { 100L })
    @After fun close() = db.close()
    @Test fun datedNotesReuseIdentityAndKeepEditsAndDeletionGenerations() = runTest {
        val first = repo.dailyNote("2026-09-30")
        repo.update(first.id) { it.copy(data = wireJson.encodeToJsonElement(DailyNote("2026-09-30", "A quiet journal", listOf("project-1"))) as JsonObject) }
        assertEquals(first.id, repo.dailyNote("2026-09-30").id)
        val saved = wireJson.decodeFromJsonElement(DailyNote.serializer(), repo.dailyNote("2026-09-30").data)
        assertEquals("A quiet journal", saved.body); assertEquals(listOf("project-1"), saved.relatedIds)
        assertEquals(first.id, repo.search("journal").single().id)
        repo.delete(first.id)
        assertEquals("daily-2026-09-30-2", repo.dailyNote("2026-09-30").id)
        repo.receive(first)
        assertTrue(repo.get(first.id)!!.deleted)
        val other = NookRepository(db, "local:other", "other")
        assertEquals(first.id, other.dailyNote("2026-09-30").id)
        assertFalse(wireJson.decodeFromJsonElement(DailyNote.serializer(), other.dailyNote("2026-09-30").data).body.contains("quiet journal"))
    }
}
