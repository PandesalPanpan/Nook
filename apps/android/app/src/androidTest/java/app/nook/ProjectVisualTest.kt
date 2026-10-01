package app.nook

import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import app.nook.data.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertTrue
import androidx.compose.ui.unit.dp
import java.time.LocalDate

/** Populated source-frame comparison through the real activity and its local database. */
class ProjectVisualTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun projectLeadsWithOutcomeNextActionAndTasks(): Unit = runBlocking {
        if(compose.onAllNodesWithText("Use Nook without an account").fetchSemanticsNodes().isNotEmpty())
            compose.onNodeWithText("Use Nook without an account").performScrollTo().performClick()
        val repo = AppGraph.repository(compose.activity)
        val created = mutableListOf<String>()
        suspend fun create(kind: String, data: JsonObject) = repo.create(kind, data).also { created.add(it.id) }
        try {
            val area = create("area", wireJson.encodeToJsonElement(Area("University")) as JsonObject)
            val project = create("project", wireJson.encodeToJsonElement(Project("Classroom Management", "Working classroom system with IoT display ready for final demo.", progress = 64.0, areaId = area.id)) as JsonObject)
            suspend fun task(value: Task) = create("task", wireJson.encodeToJsonElement(value.copy(projectId = project.id)) as JsonObject)
            task(Task("Define requirements", completed = true))
            task(Task("Choose display", completed = true))
            val next = task(Task("Build ESP32 prototype", doDate = LocalDate.now().toString(), deadline = LocalDate.now().plusDays(2).toString()))
            task(Task("Connect backend"))
            repo.update(project.id) { it.copy(data = JsonObject(it.data + ("nextActionId" to JsonPrimitive(next.id)))) }
            compose.onNodeWithText("Projects", useUnmergedTree = true).performClick()
            compose.waitUntil(5000) { compose.onAllNodesWithText("Classroom Management").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Classroom Management").performScrollTo().performClick()
            compose.waitUntil(5000) { compose.onAllNodesWithText("AREA · UNIVERSITY").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("64%").assertExists()
            compose.onNodeWithText("Define requirements").assertIsDisplayed()
            compose.onNodeWithText("Choose display").assertIsDisplayed()
            compose.onNodeWithText("Connect backend").assertIsDisplayed()
            fun assertCreationOrder() {
                val titles = listOf("Define requirements", "Choose display", "Build ESP32 prototype", "Connect backend")
                val tops = titles.map { title -> compose.onNode(hasText(title) and hasClickAction()).fetchSemanticsNode().boundsInRoot.top }
                assertTrue(tops.zipWithNext().all { (before, after) -> before < after })
            }
            assertCreationOrder()
            compose.onNodeWithContentDescription("Mark Define requirements incomplete").assertIsOn().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
            compose.onNodeWithContentDescription("Complete Build ESP32 prototype").assertIsOff().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
            compose.onNode(hasText("Outcome") and hasSetTextAction()).assertDoesNotExist()
            compose.onNode(hasText("Next action") and hasSetTextAction()).assertDoesNotExist()
            java.io.File(compose.activity.getExternalFilesDir(null), "native-project-populated.png").outputStream().use {
                compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            compose.onNodeWithContentDescription("Complete Build ESP32 prototype").performClick()
            compose.waitUntil(5000) { runBlocking { repo.get(next.id)!!.data["completed"]!!.jsonPrimitive.boolean } }
            compose.onNodeWithContentDescription("Mark Build ESP32 prototype incomplete").assertIsOn()
            compose.onNodeWithText("Start").assertDoesNotExist()
            assertCreationOrder()
            assertTrue(repo.get(next.id)!!.data["completed"]!!.jsonPrimitive.boolean)
        } finally {
            created.reversed().forEach { id -> if(repo.get(id)?.deleted == false) repo.delete(id) }
        }
    }
}
