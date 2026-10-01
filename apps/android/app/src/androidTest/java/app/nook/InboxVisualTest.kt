package app.nook

import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test

class InboxVisualTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun populatedInboxKeepsCaptureTypesAndOpensClarifyOffline(): Unit = runBlocking {
        if(compose.onAllNodesWithText("Use Nook without an account").fetchSemanticsNodes().isNotEmpty())
            compose.onNodeWithText("Use Nook without an account").performScrollTo().performClick()
        val repository = AppGraph.repository(compose.activity)
        val created = mutableListOf<String>()
        try {
            for((body, type) in listOf(
                "Research ESP32 deep sleep" to "text",
                "Send assessment follow-up" to "task",
                "Whiteboard after capstone meeting" to "image",
                "Offline-first sync article" to "link",
                "Maybe add voice capture later" to "text",
            ).reversed()) created += repository.capture(body, type).id
            compose.onNodeWithText("Inbox", useUnmergedTree = true).performClick()
            compose.waitUntil(5000) { compose.onAllNodesWithText("Research ESP32 deep sleep").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("Inbox thought").assertIsDisplayed()
            listOf("Research ESP32 deep sleep", "Send assessment follow-up", "Whiteboard after capstone meeting", "Offline-first sync article", "Maybe add voice capture later").forEach {
                compose.onNodeWithText(it).assertExists()
            }
            java.io.File(compose.activity.getExternalFilesDir(null), "native-inbox-populated.png").outputStream().use {
                compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            compose.onNodeWithText("Research ESP32 deep sleep").performClick()
            compose.waitUntil(5000) { compose.onAllNodesWithText("Clarify").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Assign to project · None").assertExists()
        } finally { created.forEach { repository.delete(it) } }
    }
}
