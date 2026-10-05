package app.nook

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.nook.data.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class CaptureProjectTest {
    @Test fun clarificationKeepsProcessedHistoryAndRejectsInvalidHomesAtomically() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), NookDatabase::class.java).build()
        val repo = NookRepository(db, "local:project-capture", "client")
        try {
            val project = repo.create("project", wireJson.encodeToJsonElement(Project("Destination")) as JsonObject)
            val capture = repo.capture("Task thought", "task")
            val task = repo.process(capture.id, "task", project.id)
            assertEquals(project.id, task.data["projectId"]!!.jsonPrimitive.content)
            assertEquals("Task thought", task.data["title"]!!.jsonPrimitive.content)
            val processed = repo.get(capture.id)!!
            assertFalse(processed.deleted)
            assertTrue(processed.data["processedAt"]!!.jsonPrimitive.long > 0)
            assertEquals(task.id, processed.data["processedIds"]!!.jsonArray.single().jsonPrimitive.content)
            val remaining = repo.capture("Keep this thought")
            repo.archive(project.id, true)
            assertTrue(runCatching { repo.process(remaining.id, "task", project.id) }.isFailure)
            assertFalse(repo.get(remaining.id)!!.deleted)
            assertTrue(runCatching { repo.process(remaining.id, "task", "missing") }.isFailure)
            assertFalse(repo.get(remaining.id)!!.deleted)
            assertNull(repo.get(remaining.id)!!.data["processedAt"])
        } finally { db.close() }
    }
}
