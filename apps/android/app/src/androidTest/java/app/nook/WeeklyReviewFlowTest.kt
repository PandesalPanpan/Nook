package app.nook

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import app.nook.data.weekStart
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate

class WeeklyReviewFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun focusDraftSurvivesRecreationAndSavesOffline() {
        if(compose.onAllNodesWithText("Use Nook without an account").fetchSemanticsNodes().isNotEmpty()) compose.onNodeWithText("Use Nook without an account").performScrollTo().performClick()
        compose.onNodeWithText("Library", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Weekly review", useUnmergedTree = true).performScrollTo().performClick()
        compose.onNodeWithText("Review", useUnmergedTree = true).performScrollTo().performClick()
        compose.onNodeWithText("Check", useUnmergedTree = true).performScrollTo().performClick()
        compose.onNodeWithText("Finish review", useUnmergedTree = true).performScrollTo().performClick()
        compose.onNodeWithText("Next focus").performScrollTo().performTextReplacement("Weekly offline focus")
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Next focus").assert(hasText("Weekly offline focus"))
        compose.onNodeWithText("Save focus", useUnmergedTree = true).performScrollTo().performClick()
        val id = "weekly-focus-${weekStart(LocalDate.now().toString())}"
        compose.waitUntil(5000) {runBlocking {AppGraph.repository(compose.activity).get(id)?.data?.get("body")?.jsonPrimitive?.content == "Weekly offline focus"}}
        compose.activityRule.scenario.recreate()
        compose.waitUntil(5000) {compose.onAllNodesWithText("Edit note").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("Edit note").performScrollTo().performClick()
        compose.onNodeWithText("Note body").assert(hasText("Weekly offline focus"))
    }
}
