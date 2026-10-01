package app.nook

import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import app.nook.data.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Rule
import org.junit.Test

/** Source-like populated agenda through MainActivity and the real offline database. */
class CalendarVisualTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun populatedCalendarKeepsDoDateAndDeadlineSeparate(): Unit = runBlocking {
        if (compose.onAllNodesWithText("Use Nook without an account").fetchSemanticsNodes().isNotEmpty())
            compose.onNodeWithText("Use Nook without an account").performScrollTo().performClick()
        val repo = AppGraph.repository(compose.activity)
        val created = mutableListOf<String>()
        try {
            for (task in listOf(
                Task("Work on Activity 3", doDate = "2026-09-30", deadline = "2026-10-02"),
                Task("20 min capstone display", doDate = "2026-09-30"),
            )) created += repo.create("task", wireJson.encodeToJsonElement(task) as JsonObject).id
            compose.onNodeWithText("Library", useUnmergedTree = true).performClick()
            compose.onNodeWithText("Calendar", useUnmergedTree = true).performScrollTo().performClick()
            compose.onNodeWithContentDescription("Agenda actions").performScrollTo().performClick(); compose.onNodeWithText("Type date").performClick()
            compose.onNodeWithText("Daily agenda · YYYY-MM-DD").performTextReplacement("2026-09-30")
            compose.onNodeWithText("Hide date entry").performScrollTo().performClick()
            compose.onNodeWithText("Work on Activity 3").assertExists()
            compose.onNodeWithText("Do date · deadline Oct 2").assertExists()
            compose.onNodeWithContentDescription("Choose 2026-10-02, scheduled work").assertExists()
            compose.onNodeWithContentDescription("Previous month").performScrollTo()
            java.io.File(compose.activity.getExternalFilesDir(null), "native-calendar-populated.png").outputStream().use {
                compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            compose.onNodeWithContentDescription("Choose 2026-10-02, scheduled work").performScrollTo().performClick()
            compose.onNodeWithText("Deadline").assertExists()
            compose.onNodeWithText("20 min capstone display").assertDoesNotExist()
        } finally { created.reversed().forEach { repo.delete(it) } }
    }
}
