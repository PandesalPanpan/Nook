package app.nook

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import app.nook.data.Record
import app.nook.data.Task
import app.nook.data.wireJson
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class TodayLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test fun largeTextActionsRemainSeparateAndReachableAtNarrowWidths() {
        val title = "Finish the accessibility review for the display module"
        val futureTitle = "Prepare the supporting materials for the project milestone"
        fun task(id: String, value: Task) = Record(id, "local:layout", "task",
            wireJson.encodeToJsonElement(value) as JsonObject, createdAt = 1, updatedAt = 1, clientId = "layout")
        val due = task("due", Task(title, doDate = "2026-09-30", deadline = "2026-10-02"))
        val future = task("future", Task(futureTitle, deadline = "2026-10-04"))
        var width by mutableStateOf(360)
        val opened = mutableListOf<String>()
        var completed = 0
        var inboxOpened = 0
        compose.setContent { NookTheme {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                Column(Modifier.width(width.dp).testTag("viewport").verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    TodayCards(listOf(due, future), "2026-09-30", 7,
                        { opened.add(it.id) }, { completed++ }, { inboxOpened++ })
                }
            }
        } }
        for (viewport in listOf(360, 280)) {
            compose.runOnIdle { width = viewport }
            val parent = compose.onNodeWithTag("viewport").fetchSemanticsNode().boundsInRoot
            val checkbox = compose.onNodeWithContentDescription("Complete $title")
            checkbox.performScrollTo().assertIsOff().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
            val checkBounds = checkbox.fetchSemanticsNode().boundsInRoot
            val action = compose.onNode(hasText(title) and hasText("Do today · Due 2026-10-02"))
            action.performScrollTo().assertIsDisplayed().assertHasClickAction().assertHeightIsAtLeast(48.dp)
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            val actionBounds = action.fetchSemanticsNode().boundsInRoot
            assertTrue(checkBounds.right <= actionBounds.left)
            action.performClick()
            checkbox.performScrollTo().performClick()
            for (label in listOf(futureTitle, "Process")) {
                val node = compose.onNodeWithText(label)
                node.performScrollTo().assertIsDisplayed().assertHasClickAction()
                    .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
                    .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
                val bounds = node.fetchSemanticsNode().boundsInRoot
                assertTrue("$label at $viewport: $bounds", bounds.left >= parent.left && bounds.right <= parent.right)
                node.performClick()
            }
            // The short summary heading must fit on one line rather than breaking inside Inbox.
            val summary = compose.onNodeWithText("Inbox · 7", useUnmergedTree = true)
            summary.performScrollTo().assertIsDisplayed()
            assertTrue(summary.fetchSemanticsNode().boundsInRoot.height <= 42f)
            java.io.File(ApplicationProvider.getApplicationContext<Context>().getExternalFilesDir(null),
                "native-today-large-text-$viewport.png").outputStream().use {
                compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
        }
        compose.runOnIdle {
            assertEquals(listOf("due", "future", "due", "future"), opened)
            assertEquals(2, completed)
            assertEquals(2, inboxOpened)
        }
    }
}
