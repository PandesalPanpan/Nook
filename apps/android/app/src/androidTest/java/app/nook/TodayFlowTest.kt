package app.nook

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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

class TodayFlowTest {
    @get:Rule val compose = createComposeRule()

    @Test fun todayCompletionPersistsAndFutureDeadlineOpensFromComingUp() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), NookDatabase::class.java).build()
        val repo = NookRepository(db, "local:today-test", "client")
        try {
            val due = repo.create("task", wireJson.encodeToJsonElement(Task("Do offline work", doDate = "2026-09-30", deadline = "2026-10-02")) as JsonObject)
            val upcoming = repo.create("task", wireJson.encodeToJsonElement(Task("Future deadline", deadline = "2026-10-04")) as JsonObject)
            val next = repo.create("task", wireJson.encodeToJsonElement(Task("Undated project action")) as JsonObject)
            repo.create("project", wireJson.encodeToJsonElement(Project("Active outcome", nextActionId = next.id)) as JsonObject)
            val hidden = repo.create("task", wireJson.encodeToJsonElement(Task("Archived project action")) as JsonObject)
            val archived = repo.create("project", wireJson.encodeToJsonElement(Project("Archived outcome", nextActionId = hidden.id)) as JsonObject)
            repo.update(archived.id) { it.copy(archived = true) }
            var opened: String? = null
            var inboxOpened = false
            compose.setContent { NookTheme {
                val records by repo.observeAll().collectAsState(initial = emptyList())
                val scope = rememberCoroutineScope()
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    TodayCards(records.filter { it.kind == "task" && it.data["completed"] != JsonPrimitive(true) }, "2026-09-30", 2,
                        { opened = it.id }, { task -> scope.launch { repo.update(task.id) { it.copy(data = JsonObject(it.data + ("completed" to JsonPrimitive(true)))) } } }, { inboxOpened = true }, projects = records.filter { it.kind == "project" })
                }
            } }
            compose.waitUntil(5000) { compose.onAllNodesWithText("Do offline work").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Do today · Due 2026-10-02").assertExists()
            compose.onAllNodesWithText("Do offline work").assertCountEquals(2)
            compose.onNodeWithText("Undated project action").assertExists()
            compose.onNodeWithText("Project next action").assertExists()
            compose.onNodeWithText("Archived project action").assertDoesNotExist()
            compose.onNodeWithText("Undated project action").performScrollTo().performClick()
            compose.runOnIdle { assertEquals(next.id, opened) }
            compose.onNodeWithContentDescription("Complete Undated project action").performScrollTo().performClick()
            compose.waitUntil(5000) { runBlocking { repo.get(next.id)?.data?.get("completed") == JsonPrimitive(true) } }
            compose.waitUntil(5000) { compose.onAllNodesWithText("Undated project action").fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithContentDescription("Complete Do offline work").performScrollTo().assertIsOff().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp).performClick()
            compose.waitUntil(5000) { runBlocking { repo.get(due.id)?.data?.get("completed") == JsonPrimitive(true) } }
            compose.onNodeWithText("Future deadline").performScrollTo().performClick()
            compose.runOnIdle { assertEquals(upcoming.id, opened) }
            compose.onNodeWithText("Process").performScrollTo().assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp).performClick()
            compose.runOnIdle { assertEquals(true, inboxOpened) }
        } finally { db.close() }
    }
}
