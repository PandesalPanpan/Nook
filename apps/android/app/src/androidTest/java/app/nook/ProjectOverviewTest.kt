package app.nook

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import app.nook.data.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ProjectOverviewTest {
    @get:Rule val compose = createComposeRule()
    @Test fun taskRowsKeepOpeningAndCompletionSeparateAtLargeText() {
        val title = "Review the classroom hardware and complete the installation checklist"
        val record = Record("task", "local:project", "task", wireJson.encodeToJsonElement(Task(title, doDate = "2026-10-01", deadline = "2026-10-05")) as JsonObject,
            createdAt = 1, updatedAt = 1, clientId = "test")
        var task by mutableStateOf(record)
        var width by mutableStateOf(360)
        var opened = 0
        var completed = 0
        compose.setContent { NookTheme { CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
            Column(Modifier.width(width.dp).background(Color(0xff171412)).verticalScroll(rememberScrollState())) {
                ProjectTaskRow(task, { opened++ }) { completed++; task = task.copy(data = JsonObject(task.data + ("completed" to JsonPrimitive(true)))) }
            }
        } } }
        for(viewport in listOf(360, 280)) {
            compose.runOnIdle { width = viewport; task = record }
            val opening = compose.onNode(hasText(title) and hasClickAction())
            opening.performScrollTo().assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
                .assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.Role, androidx.compose.ui.semantics.Role.Button))
            val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
            val target = opening.fetchSemanticsNode().boundsInRoot
            assertTrue(target.left >= root.left && target.right <= root.right)
            opening.performClick()
            compose.runOnIdle { assertEquals(0, completed) }
            compose.onNodeWithContentDescription("Complete $title").assertIsOff().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp).performClick()
            compose.onNodeWithContentDescription("Mark $title incomplete").assertIsOn()
            compose.runOnIdle { assertEquals(if(viewport == 360) 1 else 2, opened); assertEquals(1, completed); completed = 0 }
            java.io.File(ApplicationProvider.getApplicationContext<Context>().getExternalFilesDir(null), "native-project-task-large-$viewport.png").outputStream().use {
                compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }
    @Test fun summaryOpensOnlyLiveAccountNextActionsAtNormalAndLargeTextSizes() {
        fun record(id: String, kind: String, data: JsonObject) = Record(id, "local:project", kind, data, createdAt = 1, updatedAt = 1, clientId = "test")
        val area = record("area", "area", wireJson.encodeToJsonElement(Area("University")) as JsonObject)
        val next = record("next", "task", wireJson.encodeToJsonElement(Task("Finish ESP32 display prototype")) as JsonObject)
        val project = record("project", "project", wireJson.encodeToJsonElement(Project("Classroom Management", "Working classroom system with IoT display ready for final demo.", progress = 64.0, areaId = area.id, nextActionId = next.id)) as JsonObject)
        var records by mutableStateOf(listOf(area, next))
        var scale by mutableStateOf(1f)
        var width by mutableStateOf(360)
        var opened = ""
        compose.setContent { NookTheme {
            CompositionLocalProvider(LocalDensity provides Density(1f, scale)) {
                Column(Modifier.width(width.dp).background(Color(0xff171412)).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    ProjectOverview(project, records) { opened = it.id }
                }
            }
        } }
        for ((viewport, fontScale) in listOf(360 to 1f, 280 to 2f)) {
            compose.runOnIdle { width = viewport; scale = fontScale }
            compose.onNodeWithText("AREA · UNIVERSITY").assertExists()
            compose.onNodeWithText("Finish ESP32 display prototype").assertExists()
            compose.onNodeWithText("Start").performScrollTo().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp).performClick()
            compose.runOnIdle { assertEquals(next.id, opened) }
            java.io.File(ApplicationProvider.getApplicationContext<Context>().getExternalFilesDir(null), "native-project-summary-$viewport.png").outputStream().use {
                compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
        }
        for (unavailable in listOf(next.copy(deleted = true), next.copy(archived = true), next.copy(accountId = "local:other"),
            next.copy(data = JsonObject(next.data + ("completed" to JsonPrimitive(true)))))) {
            compose.runOnIdle { records = listOf(area, unavailable) }
            compose.onNodeWithText("Start").assertDoesNotExist()
            compose.onNodeWithText("Choose your next step").assertExists()
        }
    }
}
