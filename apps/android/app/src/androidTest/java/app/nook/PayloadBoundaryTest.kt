package app.nook

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.nook.data.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class PayloadBoundaryTest {
    @Test fun invalidLocalEditsAndRemotePayloadsLeaveRecordsSearchAndOutboxIntact() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), NookDatabase::class.java).build()
        val repo = NookRepository(db, "local:boundary", "client")
        try {
            val task = repo.create("task", wireJson.encodeToJsonElement(Task("Preserved proof")) as JsonObject)
            val originalOperation = db.records().operation(repo.accountId, task.id)
            val bad = JsonObject(task.data + ("projectId" to JsonPrimitive("bad/account")))
            assertTrue(runCatching { repo.update(task.id) { it.copy(data = bad) } }.isFailure)
            assertEquals(task, repo.get(task.id))
            assertEquals(originalOperation, db.records().operation(repo.accountId, task.id))
            assertEquals(listOf(task.id), repo.search("Preserved").map { it.id })
            assertTrue(runCatching { repo.create("task", bad, "invalid-create") }.isFailure)
            assertNull(repo.get("invalid-create"))
            assertNull(db.records().operation(repo.accountId, "invalid-create"))
            assertTrue(runCatching { repo.receive(task.copy(data = bad, updatedAt = task.updatedAt + 1)) }.isFailure)
            assertEquals(task, repo.get(task.id))
            assertEquals(originalOperation, db.records().operation(repo.accountId, task.id))
        } finally { db.close() }
    }
}
