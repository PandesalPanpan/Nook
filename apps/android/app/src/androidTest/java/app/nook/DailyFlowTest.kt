package app.nook

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Rule
import org.junit.Test

class DailyFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun createEditAndReopenDatedNoteOffline() {
        if(compose.onAllNodesWithText("Use Nook without an account").fetchSemanticsNodes().isNotEmpty()) compose.onNodeWithText("Use Nook without an account").performScrollTo().performClick()
        compose.onNodeWithText("Library", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Calendar", useUnmergedTree = true).performScrollTo().performClick()
        compose.onNodeWithContentDescription("Agenda actions").performScrollTo().performClick(); compose.onNodeWithText("Type date").performClick()
        compose.onNodeWithText("Daily agenda · YYYY-MM-DD").performTextReplacement("2026-09-30")
        compose.onNodeWithContentDescription("Agenda actions").performScrollTo().performClick(); compose.onNodeWithText("Open daily note", useUnmergedTree = true).performClick()
        compose.waitUntil(5000) {compose.onAllNodesWithText("What happened today?").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("Note body").performScrollTo().performTextReplacement("## Wins\n\nDaily offline journal")
        compose.onNodeWithText("Note body").assert(hasText("## Wins\n\nDaily offline journal"))
        compose.onNodeWithText("Save", useUnmergedTree = true).performScrollTo().performClick()
        compose.waitUntil(5000) {runBlocking {AppGraph.repository(compose.activity).get("daily-2026-09-30")?.data?.get("body")?.jsonPrimitive?.content == "## Wins\n\nDaily offline journal"}}
        compose.activityRule.scenario.recreate()
        compose.waitUntil(5000) {compose.onAllNodesWithText("Note body").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("Note body").assert(hasText("## Wins\n\nDaily offline journal"))
        compose.onNodeWithText("Library", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Calendar", useUnmergedTree = true).performScrollTo().performClick()
        compose.onNodeWithContentDescription("Agenda actions").performScrollTo().performClick(); compose.onNodeWithText("Type date").performClick()
        compose.onNodeWithText("Daily agenda · YYYY-MM-DD").performTextReplacement("2026-09-30")
        compose.onNodeWithContentDescription("Agenda actions").performScrollTo().performClick(); compose.onNodeWithText("Open daily note", useUnmergedTree = true).performClick()
        compose.waitUntil(5000) {compose.onAllNodesWithText("Note body").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("Note body").assert(hasText("## Wins\n\nDaily offline journal"))
    }
}
