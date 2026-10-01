package app.nook

import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import app.nook.data.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate

/** Reproducible populated screenshot of the production activity, rather than a card harness. */
class TodayVisualTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun populatedTodayRendersScheduledWorkNextActionDeadlinesAndInbox(): Unit = runBlocking {
        if (compose.onAllNodesWithText("Use Nook without an account").fetchSemanticsNodes().isNotEmpty())
            compose.onNodeWithText("Use Nook without an account").performScrollTo().performClick()
        val repo = AppGraph.repository(compose.activity)
        val created = mutableListOf<String>()
        val date = LocalDate.now()
        val existing = repo.db.records().all(repo.accountId).map { it.decode() }
        val baselineActions = todayActions(existing.filter { it.kind == "task" }, existing.filter { it.kind == "project" }, date.toString()).size
        val baselineInbox = existing.count { it.kind == "capture" && !it.deleted && !it.archived }
        suspend fun task(value: Task): Record = repo.create("task", wireJson.encodeToJsonElement(value) as JsonObject).also { created.add(it.id) }
        try {
            task(Task("Finish Activity 3", doDate = date.toString(), deadline = date.plusDays(2).toString()))
            val next = task(Task("Capstone display module"))
            repo.create("project", wireJson.encodeToJsonElement(Project("Capstone", nextActionId = next.id)) as JsonObject).also { created.add(it.id) }
            task(Task("Review application", deadline = date.toString()))
            task(Task("Capstone milestone", deadline = date.plusDays(4).toString()))
            task(Task("Embedded Systems quiz", deadline = date.plusDays(7).toString()))
            repeat(7) { created.add(repo.capture("Visual fixture thought ${it + 1}").id) }
            compose.onNodeWithText("Today", useUnmergedTree = true).performClick()
            compose.waitUntil(5000) { compose.onAllNodesWithText("Capstone display module").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Review application").assertExists()
            compose.onNodeWithText("Capstone milestone").assertExists()
            compose.onNodeWithText("Embedded Systems quiz").assertExists()
            compose.onNodeWithText("Inbox · ${baselineInbox + 7}").assertExists()
            compose.onNodeWithText("${baselineActions + 3} things worth doing.").assertExists()
            compose.mainClock.advanceTimeBy(500)
            java.io.File(compose.activity.getExternalFilesDir(null), "native-today-populated.png").outputStream().use { output ->
                compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output)
            }
        } finally {
            // Only this fixture's identities are removed, leaving unrelated device records alone.
            created.reversed().forEach { id -> if (repo.get(id)?.deleted == false) repo.delete(id) }
        }
    }
}
