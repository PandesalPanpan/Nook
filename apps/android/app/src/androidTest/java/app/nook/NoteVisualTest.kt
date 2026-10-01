package app.nook

import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import app.nook.data.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class NoteVisualTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun readingFirstNoteHasBacklinksAndMenuSavesEditsOffline(): Unit = runBlocking {
        if(compose.onAllNodesWithText("Use Nook without an account").fetchSemanticsNodes().isNotEmpty())
            compose.onNodeWithText("Use Nook without an account").performScrollTo().performClick()
        val repo = AppGraph.repository(compose.activity)
        val created = mutableListOf<String>()
        suspend fun create(kind: String, data: JsonObject) = repo.create(kind, data).also { created.add(it.id) }
        try {
            val resource = create("resource", wireJson.encodeToJsonElement(Resource("ESP32")) as JsonObject)
            val target = create("note", wireJson.encodeToJsonElement(Note("Classroom Management System", "A related note")) as JsonObject)
            val body = "Deep sleep dramatically reduces power use when the board can spend most of its time idle.\n\n## Possible uses\n\n- Battery-powered environmental node\n- Periodic sensor sampling\n- Wake by timer or GPIO\n\n[[${target.id}]]"
            val note = create("note", wireJson.encodeToJsonElement(Note("ESP32 deep sleep notes", body, resourceId = resource.id)) as JsonObject)
            create("note", wireJson.encodeToJsonElement(Note("Activity 3 research", "[[${note.id}]]")) as JsonObject)
            create("note", wireJson.encodeToJsonElement(Note("Capstone power planning", "[[${note.id}]]")) as JsonObject)
            compose.onNodeWithText("Library", useUnmergedTree = true).performClick()
            compose.onNodeWithText("Resources", useUnmergedTree = true).performScrollTo().performClick()
            compose.waitUntil(5000) { compose.onAllNodesWithText("ESP32 deep sleep notes").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("ESP32 deep sleep notes").performScrollTo().performClick()
            compose.waitUntil(5000) { compose.onAllNodesWithText("RESOURCE · ESP32").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("Title").assert(hasText("ESP32 deep sleep notes"))
            compose.onNodeWithText("Backlinks · 2").assertExists()
            compose.onNodeWithText("Note body").assertDoesNotExist()
            compose.onNodeWithText("Activity 3 research").assertExists()
            compose.onNodeWithText("Capstone power planning").assertExists()
            compose.onNodeWithContentDescription("Note actions").assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
            compose.mainClock.advanceTimeBy(500)
            java.io.File(compose.activity.getExternalFilesDir(null), "native-note-populated.png").outputStream().use {
                compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            val beforeNavigation = repo.get(note.id)!!.updatedAt
            compose.onNodeWithText("↗ Classroom Management System").performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
            compose.waitUntil(5000) { compose.onAllNodes(hasContentDescription("Title") and hasText("Classroom Management System")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("ESP32 deep sleep notes").performScrollTo().performClick()
            compose.waitUntil(5000) { compose.onAllNodes(hasContentDescription("Title") and hasText("ESP32 deep sleep notes")).fetchSemanticsNodes().isNotEmpty() }
            assertEquals(beforeNavigation, repo.get(note.id)!!.updatedAt)
            val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
            compose.onNodeWithContentDescription("Attach").performScrollTo().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp).performClick()
            compose.waitUntil(5000) { automation.rootInActiveWindow?.packageName?.toString()?.let { it.contains("documentsui") || it.contains("intentresolver") } == true }
            assertTrue(automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))
            compose.waitUntil(5000) { automation.rootInActiveWindow?.packageName?.toString() == "app.nook" }
            assertEquals(0, repo.db.records().all(repo.accountId).map { it.decode() }.count { it.kind == "attachment" && !it.deleted && it.data["ownerId"]?.jsonPrimitive?.content == note.id })
            compose.onNodeWithText("Edit note").performScrollTo().performClick()
            val changed = "## Offline changes\n\nSaved from the note menu. [[${target.id}]]"
            compose.onNodeWithText("Note body").performScrollTo().performTextReplacement(changed)
            compose.onNodeWithContentDescription("Title").performScrollTo().performTextReplacement("Updated offline note")
            compose.onNodeWithContentDescription("Note actions").performScrollTo().performClick()
            compose.onNodeWithText("Save note").performClick()
            compose.waitUntil(5000) { runBlocking { repo.get(note.id)!!.data["body"]!!.jsonPrimitive.content == changed } }
            assertEquals("Updated offline note", repo.get(note.id)!!.data["title"]!!.jsonPrimitive.content)
            compose.activityRule.scenario.recreate()
            compose.waitUntil(5000) { compose.onAllNodesWithText("Edit note").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("Title").assert(hasText("Updated offline note"))
            compose.onNodeWithText("Backlinks · 2").assertExists()
            compose.onNodeWithText("Edit note").performScrollTo().performClick()
            compose.onNodeWithText("Note body").assert(hasText(changed))
            compose.onNodeWithContentDescription("Note actions").performScrollTo().performClick()
            compose.onNodeWithText("Archive note").performClick()
            compose.waitUntil(5000) { runBlocking { repo.get(note.id)!!.archived } }
            compose.onNodeWithContentDescription("Note actions").performScrollTo().performClick()
            compose.onNodeWithText("Restore note").performClick()
            compose.waitUntil(5000) { runBlocking { !repo.get(note.id)!!.archived } }
            compose.onNodeWithContentDescription("Note actions").performScrollTo().performClick()
            compose.onNodeWithText("Delete note").performClick()
            compose.waitUntil(5000) { runBlocking { repo.get(note.id)!!.deleted } }
            compose.onNodeWithContentDescription("Title").assertDoesNotExist()
            assertTrue(repo.get(note.id)!!.deleted)
        } finally {
            created.reversed().forEach { id -> if(repo.get(id)?.deleted == false) repo.delete(id) }
        }
    }
}
