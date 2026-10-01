package app.nook

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test

class CaptureFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private fun screenshot(name: String) {
        val bitmap = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        java.io.File(compose.activity.getExternalFilesDir(null), name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    @Test fun keyboardCaptureAndProjectAssignmentUseVisibleActionsAndDateDefaults() {
        if(compose.onAllNodesWithText("Use Nook without an account").fetchSemanticsNodes().isNotEmpty()) compose.onNodeWithText("Use Nook without an account").performScrollTo().performClick()
        val projectTitle = "Capture destination proof ${java.util.UUID.randomUUID().toString().take(8)}"
        val project = kotlinx.coroutines.runBlocking {
            val repo = AppGraph.repository(compose.activity)
            repo.create("project", kotlinx.serialization.json.buildJsonObject { put("title", kotlinx.serialization.json.JsonPrimitive(projectTitle)); put("outcome", kotlinx.serialization.json.JsonPrimitive("")); put("progress", kotlinx.serialization.json.JsonPrimitive(0)) })
        }
        compose.onNodeWithText("+").performClick()
        compose.onNodeWithText("Thought").performClick().performTextInput("Keyboard project task proof")
        compose.waitUntil(5000) { compose.activity.window.decorView.rootWindowInsets?.getInsets(android.view.WindowInsets.Type.ime())?.bottom?.let { it > 0 } == true }
        compose.onNodeWithText("Photo").assertIsDisplayed()
        val save = compose.onNodeWithText("Save", useUnmergedTree = true)
        save.assertIsDisplayed()
        val bottom = save.fetchSemanticsNode().boundsInRoot.bottom
        val decor = compose.activity.window.decorView
        org.junit.Assert.assertTrue("Save must stay above the keyboard", bottom <= decor.height - decor.rootWindowInsets.getInsets(android.view.WindowInsets.Type.ime()).bottom)
        compose.onNodeWithContentDescription("Close quick capture").assertIsDisplayed().assertHasClickAction()
        screenshot("native-capture-keyboard.png")
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        compose.waitUntil(5000) { decor.rootWindowInsets.getInsets(android.view.WindowInsets.Type.ime()).bottom == 0 }
        screenshot("native-capture-current.png")
        compose.onNodeWithText("Task", useUnmergedTree = true).performClick()
        save.performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("Saved to Inbox").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Inbox", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Keyboard project task proof").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("Clarify").fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        screenshot("native-clarify-current.png")
        compose.onNodeWithText("Assign to project · None").performScrollTo().performClick()
        compose.onNodeWithText(projectTitle).performClick()
        compose.onNodeWithText("Task", useUnmergedTree = true).performScrollTo().performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("Do date · Choose date").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText("Tomorrow").onFirst().performScrollTo().performClick()
        compose.onNodeWithText("Save", useUnmergedTree = true).performScrollTo().performClick()
        compose.waitUntil(5000) { kotlinx.coroutines.runBlocking {
            val repo = AppGraph.repository(compose.activity)
            repo.db.records().byKinds(repo.accountId, listOf("task")).any {
                val record = app.nook.data.wireJson.decodeFromString<app.nook.data.Record>(it.json)
                record.data["title"] == kotlinx.serialization.json.JsonPrimitive("Keyboard project task proof") && record.data["projectId"] == kotlinx.serialization.json.JsonPrimitive(project.id) && record.data["doDate"] == kotlinx.serialization.json.JsonPrimitive(java.time.LocalDate.now().plusDays(1).toString())
            }
        } }
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Project · $projectTitle").assertExists()
    }

    @Test fun inlineInboxThoughtSavesWithoutOrganizationAndReopensOffline() {
        if(compose.onAllNodesWithText("Use Nook without an account").fetchSemanticsNodes().isNotEmpty()) compose.onNodeWithText("Use Nook without an account").performScrollTo().performClick()
        compose.onNodeWithText("Inbox", useUnmergedTree = true).performClick()
        compose.onNodeWithContentDescription("Inbox thought").performTextInput("Inline offline thought")
        compose.onNodeWithText("Save", useUnmergedTree = true).performScrollTo().performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("Inline offline thought").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Inbox thought").assert(SemanticsMatcher.expectValue(
            androidx.compose.ui.semantics.SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString("")))
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Inline offline thought").performScrollTo().performClick()
        compose.onNodeWithText("Clarify").assertExists()
        compose.onAllNodesWithText("Inline offline thought").onFirst().assertExists()
        compose.onNodeWithText("Archive", useUnmergedTree = true).performScrollTo().performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("Archived").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Library", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Archive", useUnmergedTree = true).performScrollTo().performClick()
        compose.onNodeWithText("Inline offline thought").performScrollTo().performClick()
        compose.onAllNodesWithText("Archived").onFirst().assertExists()
        compose.onNodeWithText("Restore", useUnmergedTree = true).performScrollTo().performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("Restored").fetchSemanticsNodes().isNotEmpty() }
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Inline offline thought").assertExists()
    }

    @Test fun captureClarifyAndReopen() {
        if (compose.onAllNodesWithText("Use Nook without an account").fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithText("Use Nook without an account").performScrollTo().performClick()
        } else compose.onNodeWithText("Today", useUnmergedTree = true).performClick()
        compose.onNodeWithText("+").performClick()
        compose.onNodeWithText("Thought").performTextInput("Native offline proof")
        compose.onNodeWithText("Save", useUnmergedTree = true).performScrollTo().performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("Saved to Inbox").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Inbox", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Native offline proof").performClick()
        compose.onNodeWithText("Task", useUnmergedTree = true).performScrollTo().performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("Title").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Title").assert(hasText("Native offline proof"))
        compose.onNode(hasText("Today") and hasAnyAncestor(hasTestTag("date-choice-Do date"))).performScrollTo().performClick()
        compose.onNodeWithText("Do date · ${java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM))}").assertExists()
        // The conversion snackbar temporarily covers the bottom of the scrolling editor.
        compose.waitUntil(12000) { compose.onAllNodesWithText("Organized locally").fetchSemanticsNodes().isEmpty() && compose.onAllNodesWithText("Saved to Inbox").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("Save", useUnmergedTree = true).performScrollTo().performClick()
        compose.waitUntil(5000) { kotlinx.coroutines.runBlocking {
            val repo = AppGraph.repository(compose.activity)
            repo.db.records().byKinds(repo.accountId, listOf("task")).any {
                val record = app.nook.data.wireJson.decodeFromString<app.nook.data.Record>(it.json)
                record.data["title"] == kotlinx.serialization.json.JsonPrimitive("Native offline proof") && record.data["doDate"] == kotlinx.serialization.json.JsonPrimitive(java.time.LocalDate.now().toString())
            }
        } }
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Title").assert(hasText("Native offline proof"))
        compose.onNodeWithText("Do date · ${java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM))}").assertExists()
        compose.onNodeWithText("Weekly", useUnmergedTree = true).performScrollTo().performClick()
        compose.onNodeWithText("Save repeat schedule", useUnmergedTree = true).performScrollTo().performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("Repeat schedule saved").fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(isToggleable() and hasAnySibling(hasText("Completed"))).performScrollTo().performClick()
        compose.onNodeWithText("Save", useUnmergedTree = true).performScrollTo().performClick()
        compose.waitUntil(5000) {
            kotlinx.coroutines.runBlocking {
                val repo = AppGraph.repository(compose.activity)
                repo.db.records().byKinds(repo.accountId, listOf("task")).map { app.nook.data.wireJson.decodeFromString<app.nook.data.Record>(it.json) }
                    .count { it.data["title"] == kotlinx.serialization.json.JsonPrimitive("Native offline proof") } == 2
            }
        }
        compose.activityRule.scenario.recreate()
        compose.onNode(isToggleable() and hasAnySibling(hasText("Completed"))).assertIsOn()
    }
}
