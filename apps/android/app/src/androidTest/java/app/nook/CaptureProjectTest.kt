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
    @Test fun projectAssignmentAndCaptureDeletionCommitTogetherAndInvalidDestinationLeavesCapture() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), NookDatabase::class.java).build()
        val repo = NookRepository(db, "local:project-capture", "client")
        try {
            val project = repo.create("project", wireJson.encodeToJsonElement(Project("Destination")) as JsonObject)
            val capture = repo.capture("Task thought", "task")
            val task = repo.process(capture.id, "task", project.id)
            assertEquals(project.id, task.data["projectId"]!!.jsonPrimitive.content)
            assertEquals("Task thought", task.data["title"]!!.jsonPrimitive.content)
            assertTrue(repo.get(capture.id)!!.deleted)
            val remaining = repo.capture("Keep this thought")
            repo.archive(project.id, true)
            assertTrue(runCatching { repo.process(remaining.id, "task", project.id) }.isFailure)
            assertFalse(repo.get(remaining.id)!!.deleted)
            assertTrue(runCatching { repo.process(remaining.id, "task", "missing") }.isFailure)
            assertFalse(repo.get(remaining.id)!!.deleted)
        } finally { db.close() }
    }
}
