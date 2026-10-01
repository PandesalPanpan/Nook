package app.nook

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.nook.data.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class CalendarAgendaTest {
    @get:Rule val compose = createComposeRule()
    @Test fun invalidDateHidesUndatedWorkAndValidDateOpensItsLocalDailyNote() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), NookDatabase::class.java).build()
        val repo = NookRepository(db, "local:calendar-agenda", "client")
        try {
            repo.create("task", wireJson.encodeToJsonElement(Task("Unscheduled proof")) as JsonObject)
            val scheduledTask = repo.create("task", wireJson.encodeToJsonElement(Task("Scheduled proof", doDate = "2026-09-30", deadline = "2026-10-02")) as JsonObject)
            val archived = repo.create("task", wireJson.encodeToJsonElement(Task("Archived work", doDate = "2026-09-30")) as JsonObject)
            repo.archive(archived.id, true)
            val reminderTime = java.time.LocalDate.of(2026, 10, 1).atTime(10, 0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
            repo.create("reminder", wireJson.encodeToJsonElement(Reminder(scheduledAt = reminderTime, type = "inbox")) as JsonObject)
            // Insert the later reminder first: rendered times must be chronological, not insertion order.
            repo.create("reminder", wireJson.encodeToJsonElement(Reminder(targetId = scheduledTask.id, scheduledAt = reminderTime - 3600000, type = "task")) as JsonObject)
            repo.create("reminder", wireJson.encodeToJsonElement(Reminder(targetId = "missing-target", scheduledAt = reminderTime + 86400000, type = "deadline")) as JsonObject)
            var opened = ""
            compose.setContent { NookTheme {
                val records by repo.observeAll().collectAsState(initial = emptyList())
                val scope = rememberCoroutineScope()
                Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).verticalScroll(rememberScrollState())) {
                    CalendarScreen(records + scheduledTask.copy(id = "foreign", accountId = "local:other", data = wireJson.encodeToJsonElement(Task("Other account work", doDate = "2026-09-30")) as JsonObject), repo, { opened = it.id }, { _, work -> scope.launch { work() } })
                }
            } }
            compose.onNodeWithText("Daily agenda · YYYY-MM-DD").assertDoesNotExist()
            compose.onNodeWithContentDescription("Agenda actions").performScrollTo().performClick(); compose.onNodeWithText("Type date").performClick()
            val date = compose.onNodeWithText("Daily agenda · YYYY-MM-DD")
            date.performTextReplacement("2024-01-31")
            compose.onNodeWithContentDescription("Next month").performScrollTo().performClick()
            date.assert(hasText("2024-02-29"))
            compose.onNodeWithText("February 2024").assertExists()
            compose.onNodeWithContentDescription("Previous month").performClick()
            date.assert(hasText("2024-01-29"))
            date.performTextReplacement("9999-12-31")
            compose.onNodeWithContentDescription("Next month").assertIsNotEnabled()
            compose.onNodeWithContentDescription("Choose +10000-01-01").assertIsNotEnabled()
            listOf("", "2026-02-30", "+10000-01-01").forEach { invalid ->
                date.performTextReplacement(invalid)
                compose.onNodeWithContentDescription("Agenda actions").performScrollTo().performClick(); compose.onNodeWithText("Open daily note").assertIsNotEnabled(); compose.onNodeWithText("Type date").performClick()
                compose.onNodeWithText("Unscheduled proof").assertDoesNotExist()
                compose.onNodeWithText("Scheduled proof").assertDoesNotExist()
            }
            date.performTextReplacement("2026-09-30")
            compose.waitUntil(5000) { compose.onAllNodesWithText("Scheduled proof").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("Choose 2026-09-29").performScrollTo().performClick()
            date.assert(hasText("2026-09-29"))
            compose.onNodeWithText("Scheduled proof").assertDoesNotExist()
            compose.onNodeWithContentDescription("Choose 2026-09-30, scheduled work").performScrollTo().performClick()
            date.assert(hasText("2026-09-30"))
            compose.onNodeWithText("Unscheduled proof").assertDoesNotExist()
            compose.onNodeWithText("Do date · deadline Oct 2").assertExists()
            compose.onNodeWithText("Archived work").assertDoesNotExist()
            compose.onNodeWithText("Other account work").assertDoesNotExist()
            compose.onNodeWithContentDescription("Choose 2026-10-01, scheduled work").performScrollTo().performClick()
            compose.onNodeWithText("Inbox review").assertExists()
            compose.onNodeWithText("10:00 AM").assertExists()
            compose.onNodeWithText("9:00 AM").assertExists()
            org.junit.Assert.assertTrue(compose.onNodeWithText("9:00 AM").fetchSemanticsNode().boundsInRoot.top < compose.onNodeWithText("10:00 AM").fetchSemanticsNode().boundsInRoot.top)
            // The deadline remains scheduled even though the orphan reminder is ignored.
            compose.onNodeWithContentDescription("Choose 2026-10-02, scheduled work").assertExists()
            compose.onNodeWithText("Hide date entry").performScrollTo().performClick()
            date.assertDoesNotExist()
            compose.onNodeWithText("Inbox review").assertExists()
            java.io.File(ApplicationProvider.getApplicationContext<Context>().getExternalFilesDir(null), "native-calendar-agenda.png").outputStream().use { output ->
                compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output)
            }
            // A remote target tombstone can arrive before its reminder tombstone.
            repo.receive(scheduledTask.copy(deleted = true, updatedAt = scheduledTask.updatedAt + 1))
            compose.waitUntil(5000) { compose.onAllNodesWithText("9:00 AM").fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithText("Inbox review").assertExists()
            compose.onNodeWithContentDescription("Choose 2026-09-30, scheduled work").assertDoesNotExist()
            compose.onNodeWithContentDescription("Agenda actions").performScrollTo().performClick(); compose.onNodeWithText("Type date").performClick()
            date.assert(hasText("2026-10-01"))
            date.performTextReplacement("2026-09-30")
            compose.onNodeWithContentDescription("Agenda actions").performScrollTo().performClick(); compose.onNodeWithText("Open daily note").performClick()
            compose.waitUntil(5000) { opened.isNotEmpty() }
            assertEquals("2026-09-30", repo.get(opened)!!.data["date"]!!.jsonPrimitive.content)
        } finally { db.close() }
    }
}
