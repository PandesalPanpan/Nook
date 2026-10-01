package app.nook.integration

import app.nook.data.*
import app.nook.data.Record
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import org.junit.Assert.*
import org.junit.Test

class ReminderPolicyTest {
    private fun record(kind: String, id: String, data: JsonObject, account: String = "local:one") = Record(id, account, kind, data, createdAt = 1, updatedAt = 1, clientId = "android")
    private val inbox = record("reminder", "review", wireJson.encodeToJsonElement(Reminder(scheduledAt = 100, type = "inbox")) as JsonObject)
    @Test fun emptyInboxCanBeSuppressedAndOtherAccountsNeverCount() {
        assertNull(reminderMessage(inbox, emptyList()))
        val capture = record("capture", "thought", wireJson.encodeToJsonElement(Capture("Thought")) as JsonObject, "local:two")
        assertNull(reminderMessage(inbox, listOf(capture)))
        assertTrue(reminderMessage(inbox, listOf(capture.copy(accountId = inbox.accountId)))!!.second.startsWith("1 thing is"))
        val allowEmpty = inbox.copy(data = wireJson.encodeToJsonElement(Reminder(scheduledAt = 100, type = "inbox", skipIfInboxEmpty = false)) as JsonObject)
        assertNotNull(reminderMessage(allowEmpty, emptyList()))
    }
    @Test fun completedDeletedAndArchivedTasksDoNotNotify() {
        val reminder = record("reminder", "task-reminder", wireJson.encodeToJsonElement(Reminder("task", 100, "deadline")) as JsonObject)
        val task = record("task", "task", wireJson.encodeToJsonElement(Task("Deadline task")) as JsonObject)
        assertEquals("Deadline reminder", reminderMessage(reminder, listOf(task))!!.second)
        assertNull(reminderMessage(reminder, listOf(task.copy(deleted = true))))
        assertNull(reminderMessage(reminder, listOf(task.copy(archived = true))))
        assertNull(reminderMessage(reminder, listOf(task.copy(data = wireJson.encodeToJsonElement(Task("Deadline task", completed = true)) as JsonObject))))
        assertNull(reminderMessage(reminder.copy(deleted = true), listOf(task)))
    }
}
