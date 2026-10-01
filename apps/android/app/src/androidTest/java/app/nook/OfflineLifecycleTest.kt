package app.nook

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import app.nook.data.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class OfflineLifecycleTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun captureEditCompleteMoveSearchDeleteAndReopenUseDurableLocalRecords() {
        if(compose.onAllNodesWithText("Use Nook without an account").fetchSemanticsNodes().isNotEmpty()) compose.onNodeWithText("Use Nook without an account").performScrollTo().performClick()
        val repo = AppGraph.repository(compose.activity)
        val project = runBlocking { repo.create("project", wireJson.encodeToJsonElement(Project("OfflineDestination")) as JsonObject) }
        compose.onNodeWithText("Inbox", useUnmergedTree = true).performClick()
        compose.onNodeWithContentDescription("Inbox thought").performTextInput("Lifecycle capture")
        compose.onNodeWithText("Save", useUnmergedTree = true).performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("Lifecycle capture").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Lifecycle capture").performClick()
        compose.onNodeWithText("Task", useUnmergedTree = true).performScrollTo().performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("Title").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Title").performScrollTo().performTextReplacement("Offline lifecycle proof")
        compose.onNodeWithText("Project · None").performScrollTo().performClick()
        compose.onNodeWithText("OfflineDestination", useUnmergedTree = true).performClick()
        compose.onNode(isToggleable() and hasAnySibling(hasText("Completed"))).performScrollTo().performClick()
        compose.onNodeWithText("Save", useUnmergedTree = true).performScrollTo().performClick()
        var taskId = ""
        compose.waitUntil(5000) { runBlocking {
            val task = repo.db.records().byKinds(repo.accountId, listOf("task")).map { wireJson.decodeFromString<Record>(it.json) }.find { it.data["title"] == JsonPrimitive("Offline lifecycle proof") }
            taskId = task?.id.orEmpty()
            task?.data?.get("projectId") == JsonPrimitive(project.id) && task.data["completed"] == JsonPrimitive(true)
        } }
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Title").assert(hasText("Offline lifecycle proof"))
        compose.onNodeWithText("Project · OfflineDestination").assertExists()
        compose.onNode(isToggleable() and hasAnySibling(hasText("Completed"))).assertIsOn()
        compose.onNodeWithText("Library", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Search", useUnmergedTree = true).performScrollTo().performClick()
        compose.onNodeWithText("Search your Nook").performTextInput("project:OfflineDestination lifecycle")
        compose.waitUntil(5000) { compose.onAllNodesWithText("Offline lifecycle proof").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Offline lifecycle proof").performClick()
        compose.onNodeWithText("Delete", useUnmergedTree = true).performScrollTo().performClick()
        compose.waitUntil(5000) { runBlocking { repo.get(taskId)?.deleted == true } }
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Library", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Search", useUnmergedTree = true).performScrollTo().performClick()
        compose.onNodeWithText("Search your Nook").performTextInput("lifecycle")
        compose.waitForIdle()
        runBlocking { assertFalse(repo.search("lifecycle").any { it.id == taskId }); assertTrue(repo.get(taskId)!!.deleted) }
        compose.onNodeWithText("Offline lifecycle proof").assertDoesNotExist()
    }
}
