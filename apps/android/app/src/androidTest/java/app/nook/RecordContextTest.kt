package app.nook

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.nook.data.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class RecordContextTest {
    @get:Rule val compose = createComposeRule()
    @Test fun openingBacklinkSavesTheCurrentNoteDraft() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), NookDatabase::class.java).build()
        val repo = NookRepository(db, "local:backlink-draft", "client")
        try {
            val target = repo.create("note", wireJson.encodeToJsonElement(Note("Destination", "Before edit")) as JsonObject)
            repo.create("note", wireJson.encodeToJsonElement(Note("Source", "[[Destination]]")) as JsonObject)
            var selected by mutableStateOf(target.id)
            compose.setContent { NookTheme {
                val records by repo.observeAll().collectAsState(initial = emptyList())
                val scope = rememberCoroutineScope()
                records.find { it.id == selected }?.let { current -> key(selected) {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        RecordScreen(current, records, repo, { selected = it.id }, { _, work -> scope.launch { work() } })
                    }
                } }
            } }
            compose.waitUntil(5000) { compose.onAllNodesWithText("Edit note").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Edit note").performScrollTo().performClick()
            compose.onNodeWithText("Note body").performScrollTo().performTextReplacement("Preserved note draft")
            compose.onNodeWithText("Source").performScrollTo().performClick()
            compose.waitUntil(5000) { compose.onAllNodes(hasText("Source") and hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
            assertEquals("Preserved note draft", repo.get(target.id)!!.data["body"]!!.jsonPrimitive.content)
        } finally { db.close() }
    }
    @Test fun editorRefreshesPristineDraftsPreservesDirtyDraftsAndSavesAreaContext() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(),NookDatabase::class.java).build()
        val repo = NookRepository(db,"local:${UUID.randomUUID()}","client")
        try {
            val area = repo.create("area",wireJson.encodeToJsonElement(Area("Health")) as JsonObject)
            val project = repo.create("project",wireJson.encodeToJsonElement(Project("Garden","Original")) as JsonObject)
            compose.setContent { NookTheme {
                val records by repo.observeAll().collectAsState(initial = emptyList())
                val scope = rememberCoroutineScope()
                records.find { it.id == project.id }?.let { current -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    RecordScreen(current,records,repo,{}, { _,work -> scope.launch { work() } })
                } }
            } }
            compose.waitUntil(5000) { compose.onAllNodesWithText("Outcome").fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(hasText("Outcome") and hasSetTextAction()).assertDoesNotExist()
            compose.onNodeWithText("Edit project details").performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
            repo.update(project.id) { it.copy(data = JsonObject(it.data + ("outcome" to JsonPrimitive("Remote refresh")))) }
            compose.waitUntil(5000) { compose.onAllNodes(hasText("Remote refresh") and hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(hasText("Outcome") and hasSetTextAction()).performScrollTo().performTextReplacement("Unsaved local outcome")
            repo.update(project.id) { it.copy(data = JsonObject(it.data + ("outcome" to JsonPrimitive("Remote while dirty")))) }
            compose.waitForIdle()
            compose.onNode(hasText("Outcome") and hasSetTextAction()).assert(hasText("Unsaved local outcome"))
            compose.onNodeWithText("Hide project details").performScrollTo().performClick()
            compose.onNode(hasText("Outcome") and hasSetTextAction()).assertDoesNotExist()
            compose.onNodeWithText("Edit project details").performScrollTo().performClick()
            compose.onNode(hasText("Outcome") and hasSetTextAction()).assert(hasText("Unsaved local outcome"))
            compose.onNodeWithText("Area · None").performScrollTo().performClick()
            compose.onNodeWithText("Health",useUnmergedTree = true).performClick()
            compose.onNodeWithText("Save",useUnmergedTree = true).performScrollTo().performClick()
            compose.waitUntil(5000) { runBlocking { repo.get(project.id)!!.data["areaId"]?.jsonPrimitive?.content == area.id } }
            assertEquals("Unsaved local outcome",repo.get(project.id)!!.data["outcome"]!!.jsonPrimitive.content)
            assertEquals(1,repo.observeAll().first().count { it.kind == "project" })
        } finally { db.close() }
    }
    @Test fun linkedAreaWorkOpensAndNewTasksRetainProjectAndArea() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context,NookDatabase::class.java).build()
        val repo = NookRepository(db,"local:${UUID.randomUUID()}","client")
        try {
            val area = repo.create("area",wireJson.encodeToJsonElement(Area("Health")) as JsonObject)
            val project = repo.create("project",wireJson.encodeToJsonElement(Project("Garden",areaId = area.id)) as JsonObject)
            var selected by mutableStateOf(area.id)
            compose.setContent { NookTheme {
                val records by repo.observeAll().collectAsState(initial = emptyList())
                val scope = rememberCoroutineScope()
                records.find { it.id == selected }?.let { current -> key(selected) { Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).verticalScroll(rememberScrollState())) {
                    RecordScreen(current,records,repo,{ selected = it.id }, { _,work -> scope.launch { work() } })
                } } }
            } }
            compose.waitUntil(5000) { compose.onAllNodesWithText("Active projects").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Garden").performScrollTo()
            java.io.File(context.getExternalFilesDir(null),"native-area-related.png").outputStream().use { output -> compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG,100,output) }
            compose.onNodeWithText("Garden").performClick()
            compose.waitUntil(5000) { compose.onAllNodesWithText("Outcome").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("+ New task").performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
            compose.onNode(hasText("Next action") and hasSetTextAction()).performScrollTo().performTextReplacement("Plant seeds")
            compose.onNodeWithText("Hide new task").performScrollTo().performClick()
            compose.onNode(hasText("Next action") and hasSetTextAction()).assertDoesNotExist()
            compose.onNodeWithText("+ New task").performScrollTo().performClick()
            compose.onNode(hasText("Next action") and hasSetTextAction()).assert(hasText("Plant seeds"))
            compose.onNodeWithText("Add task",useUnmergedTree = true).performScrollTo().performClick()
            compose.waitUntil(5000) { runBlocking { repo.observeAll().first().any { it.kind == "task" } } }
            val task = repo.observeAll().first().single { it.kind == "task" }
            assertEquals(project.id,task.data["projectId"]!!.jsonPrimitive.content)
            assertEquals(area.id,task.data["areaId"]!!.jsonPrimitive.content)
            compose.onNodeWithText("Edit project details").performScrollTo().performClick()
            compose.onNode(hasText("Outcome") and hasSetTextAction()).performScrollTo().performTextReplacement("Preserved before task navigation")
            compose.onNodeWithText("Plant seeds").performScrollTo().performClick()
            compose.waitUntil(5000) { compose.onAllNodesWithText("Do date · Choose date").fetchSemanticsNodes().isNotEmpty() }
            assertEquals("Preserved before task navigation", repo.get(project.id)!!.data["outcome"]!!.jsonPrimitive.content)
            compose.runOnIdle { selected = project.id }
            compose.waitUntil(5000) { compose.onAllNodesWithText("Outcome").fetchSemanticsNodes().isNotEmpty() }
            val child = repo.create("task", wireJson.encodeToJsonElement(Task("Inherited next step", parentTaskId = task.id)) as JsonObject)
            compose.waitUntil(5000) { compose.onAllNodesWithText("Inherited next step").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Edit project details").performScrollTo().performClick()
            compose.onNodeWithText("Next action · None").performScrollTo().performClick()
            compose.onAllNodesWithText("Inherited next step", useUnmergedTree = true).onLast().performClick()
            compose.onNodeWithText("Save",useUnmergedTree = true).performScrollTo().performClick()
            compose.waitUntil(5000) { runBlocking { repo.get(project.id)!!.data["nextActionId"]?.jsonPrimitive?.content == child.id } }
            compose.onNodeWithText("Add note",useUnmergedTree = true).performScrollTo().performClick()
            compose.waitUntil(5000) { compose.onAllNodesWithText("Note body").fetchSemanticsNodes().isNotEmpty() }
            assertEquals(project.id,repo.observeAll().first().single { it.kind == "note" }.data["projectId"]!!.jsonPrimitive.content)
        } finally { db.close() }
    }
}
