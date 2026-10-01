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
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class RecurrenceTest {
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), NookDatabase::class.java).build()
    private val repo = NookRepository(db, "local:test", "android", { 100L })
    @After fun close() = db.close()
    private suspend fun task(title: String = "Walk") = repo.create("task", wireJson.encodeToJsonElement(Task(title, doDate = "2026-09-30", deadline = "2026-10-02")) as JsonObject)
    private suspend fun complete(id: String, completed: Boolean = true) = repo.update(id) { record ->
        val task = wireJson.decodeFromJsonElement(Task.serializer(), record.data)
        record.copy(data = wireJson.encodeToJsonElement(task.copy(completed = completed)) as JsonObject)
    }
    @Test fun monthEndCadenceAndSharedIdentity() {
        val rule = Recurrence("monthly", 1, "2028-01-31")
        assertEquals("2028-02-29", nextOccurrence(rule, LocalDate.parse("2028-01-31")).toString())
        assertEquals("2028-03-31", nextOccurrence(rule, LocalDate.parse("2028-02-29")).toString())
        assertEquals("2026-10-14", nextOccurrence(Recurrence("weekly", 2, "2026-09-30"), LocalDate.parse("2026-10-01")).toString())
        assertEquals("repeat-e1dd1daa7bac880817b9a67a8caed605667163a147e08c6bcfc4c924f2559b8d", occurrenceId("schedule-1", "2026-10-07"))
    }
    @Test fun completionPreservesHistoryAndNeverDuplicatesOrResurrectsOccurrence() = runTest {
        val task = task()
        repo.setRecurrence(task.id, Recurrence("weekly", 1, "2026-09-30"))
        complete(task.id)
        val next = db.records().all(repo.accountId).map { it.decode() }.single { it.kind == "task" && it.id != task.id }
        val data = wireJson.decodeFromJsonElement(Task.serializer(), next.data)
        assertEquals("2026-10-07", data.doDate); assertEquals("2026-10-09", data.deadline); assertFalse(data.completed)
        complete(task.id, false); complete(task.id)
        assertEquals(2, db.records().all(repo.accountId).count { it.kind == "task" })
        repo.delete(next.id); complete(task.id, false); complete(task.id)
        assertTrue(repo.get(next.id)!!.deleted)
    }
    @Test fun invalidConfigurationRollsBackAndDisablingStopsNewOccurrences() = runTest {
        val task = task()
        try { repo.setRecurrence(task.id, Recurrence("daily", 0, "2026-09-30")); fail("Expected rejection") } catch(_: IllegalArgumentException) { }
        assertEquals(0, db.records().all(repo.accountId).count { it.kind == "recurrence" })
        repo.setRecurrence(task.id, Recurrence("daily", 1, "2026-09-30")); repo.setRecurrence(task.id, null)
        complete(task.id)
        assertEquals(1, db.records().all(repo.accountId).count { it.kind == "task" })
    }
    @Test fun remindersAndProjectNextActionAdvanceWithTheOccurrence() = runTest {
        val project = repo.create("project", wireJson.encodeToJsonElement(Project("Garden")) as JsonObject)
        val task = repo.create("task", wireJson.encodeToJsonElement(Task("Water", doDate = "2026-09-30", projectId = project.id)) as JsonObject)
        val scheduled = LocalDate.parse("2026-09-30").atTime(9, 0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val reminder = repo.create("reminder", wireJson.encodeToJsonElement(Reminder(task.id, scheduled, "task")) as JsonObject)
        repo.update(task.id) { it.copy(data = wireJson.encodeToJsonElement(wireJson.decodeFromJsonElement(Task.serializer(), it.data).copy(reminderId = reminder.id)) as JsonObject) }
        repo.update(project.id) { it.copy(data = wireJson.encodeToJsonElement(Project("Garden", nextActionId = task.id)) as JsonObject) }
        repo.setRecurrence(task.id, Recurrence("weekly", 1, "2026-09-30")); complete(task.id)
        val next = db.records().all(repo.accountId).map { it.decode() }.single { it.kind == "task" && it.id != task.id }
        val nextTask = wireJson.decodeFromJsonElement(Task.serializer(), next.data)
        val nextReminder = wireJson.decodeFromJsonElement(Reminder.serializer(), repo.get(nextTask.reminderId!!)!!.data)
        assertEquals(next.id, nextReminder.targetId)
        val localTime = java.time.Instant.ofEpochMilli(nextReminder.scheduledAt).atZone(java.time.ZoneId.systemDefault())
        assertEquals("2026-10-07", localTime.toLocalDate().toString()); assertEquals(9, localTime.hour)
        assertEquals(next.id, wireJson.decodeFromJsonElement(Project.serializer(), repo.get(project.id)!!.data).nextActionId)
    }
}
