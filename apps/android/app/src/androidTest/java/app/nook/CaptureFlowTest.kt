package app.nook

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import kotlinx.serialization.json.*
import org.junit.Rule
import org.junit.Test

class CaptureFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private fun screenshot(name: String) {
        val bitmap = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        java.io.File(compose.activity.getExternalFilesDir(null), name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    @Test fun keyboardCaptureAndOptionalProjectScheduleUseClarificationControls() {
        if(compose.onAllNodesWithText("Use Nook without an account").fetchSemanticsNodes().isNotEmpty()) compose.onNodeWithText("Use Nook without an account").performScrollTo().performClick()
        val projectTitle = "Capture destination proof ${java.util.UUID.randomUUID().toString().take(8)}"
        val body = "Keyboard project task proof ${java.util.UUID.randomUUID().toString().take(8)}"
        val project = kotlinx.coroutines.runBlocking {
            val repo = AppGraph.repository(compose.activity)
            repo.create("project", kotlinx.serialization.json.buildJsonObject { put("title", kotlinx.serialization.json.JsonPrimitive(projectTitle)); put("outcome", kotlinx.serialization.json.JsonPrimitive("")); put("progress", kotlinx.serialization.json.JsonPrimitive(0)) })
        }
        compose.onNodeWithText("+").performClick()
        compose.onNodeWithText("Thought").performClick().performTextInput(body)
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
        compose.onNodeWithText(body).performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("Clarify").fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        screenshot("native-clarify-current.png")
        compose.onAllNodesWithText("Add to…", useUnmergedTree = true).onLast().performScrollTo().performClick()
        compose.onNodeWithText("Add to a Project, Area, or Resource").assertExists()
        compose.onNodeWithText(projectTitle).performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("Schedule · Choose date").fetchSemanticsNodes().isNotEmpty() }
        val repo = AppGraph.repository(compose.activity)
        val captureId = kotlinx.coroutines.runBlocking { repo.db.records().byKinds(repo.accountId, listOf("capture")).map { app.nook.data.wireJson.decodeFromString<app.nook.data.Record>(it.json) }.single { it.data["body"] == kotlinx.serialization.json.JsonPrimitive(body) }.id }
        compose.onNodeWithText("Schedule · Choose date").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithText("Set date").assertExists()
        compose.onAllNodesWithText("Tomorrow").onLast().performClick()
        compose.onNodeWithText("Set date").performClick()
        val tomorrow = java.time.LocalDate.now().plusDays(1).toString()
        compose.waitUntil(5000) { kotlinx.coroutines.runBlocking { repo.get(captureId)?.data?.get("clarificationDraft")?.let { app.nook.data.wireJson.decodeFromJsonElement(app.nook.data.ClarificationDraft.serializer(), it).doDate } == tomorrow } }
        compose.onNodeWithText("Schedule · Choose date").assertDoesNotExist()
        if(compose.onAllNodesWithText("Save & next", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
            compose.onNodeWithText("Save & next", useUnmergedTree = true).performScrollTo().performClick()
        else compose.onNodeWithText("Save", useUnmergedTree = true).performScrollTo().performClick()
        compose.waitUntil(5000) { kotlinx.coroutines.runBlocking {
            repo.db.records().byKinds(repo.accountId, listOf("task")).map { app.nook.data.wireJson.decodeFromString<app.nook.data.Record>(it.json) }
                .any { it.data["title"] == kotlinx.serialization.json.JsonPrimitive(body) }
        } }
        val savedTask = kotlinx.coroutines.runBlocking {
            repo.db.records().byKinds(repo.accountId, listOf("task")).map { app.nook.data.wireJson.decodeFromString<app.nook.data.Record>(it.json) }
                .single { it.data["title"] == kotlinx.serialization.json.JsonPrimitive(body) }
        }
        org.junit.Assert.assertEquals(project.id, savedTask.data["projectId"]?.jsonPrimitive?.content)
        org.junit.Assert.assertEquals(tomorrow, savedTask.data["doDate"]?.jsonPrimitive?.content)
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Projects", useUnmergedTree = true).performClick()
        compose.onNodeWithText(projectTitle).performScrollTo().performClick()
        compose.onNodeWithText(body).performScrollTo().assertExists()
    }

    @Test fun inlineInboxThoughtSavesWithoutOrganizationAndReopensOffline() {
        if(compose.onAllNodesWithText("Use Nook without an account").fetchSemanticsNodes().isNotEmpty()) compose.onNodeWithText("Use Nook without an account").performScrollTo().performClick()
        val repo = AppGraph.repository(compose.activity)
        val body = "Inline offline thought ${java.util.UUID.randomUUID().toString().take(8)}"
        compose.onAllNodesWithText("Inbox", useUnmergedTree = true).onLast().performClick()
        compose.onNodeWithContentDescription("Inbox thought").performTextInput(body)
        compose.onNodeWithText("Save", useUnmergedTree = true).performScrollTo().performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText(body).fetchSemanticsNodes().isNotEmpty() }
        val captureId = kotlinx.coroutines.runBlocking { repo.db.records().byKinds(repo.accountId, listOf("capture")).map { app.nook.data.wireJson.decodeFromString<app.nook.data.Record>(it.json) }.single { it.data["body"] == kotlinx.serialization.json.JsonPrimitive(body) }.id }
        compose.onNodeWithContentDescription("Inbox thought").assert(SemanticsMatcher.expectValue(
            androidx.compose.ui.semantics.SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString("")))
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText(body).performScrollTo().performClick()
        compose.onNodeWithText("Clarify").assertExists()
        compose.onAllNodesWithText(body).onFirst().assertExists()
        compose.onNodeWithText("More options").performScrollTo().performClick()
        compose.onNodeWithText("More  ⋯").performScrollTo().performClick()
        compose.onNodeWithText("Archive thought").performScrollTo().performClick()
        compose.waitUntil(5000) { kotlinx.coroutines.runBlocking { repo.get(captureId)?.archived == true } }
        compose.onNodeWithText("Library", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Archive", useUnmergedTree = true).performScrollTo().performClick()
        compose.onNodeWithText(body).performScrollTo().performClick()
        compose.onNodeWithText("ARCHIVED THOUGHT").assertExists()
        compose.onNodeWithText("Restore to Inbox").performScrollTo().performClick()
        compose.waitUntil(5000) { kotlinx.coroutines.runBlocking { repo.get(captureId)?.archived == false } }
        compose.activityRule.scenario.recreate()
        compose.onAllNodesWithText("Inbox", useUnmergedTree = true).onLast().performClick()
        compose.onNodeWithText(body).assertExists()
    }

    @Test fun taskClarificationIsScheduledKeptInHistoryAndRetainsRecurrence() {
        if (compose.onAllNodesWithText("Use Nook without an account").fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithText("Use Nook without an account").performScrollTo().performClick()
        } else compose.onNodeWithText("Today", useUnmergedTree = true).performClick()
        compose.onNodeWithText("+").performClick()
        val body = "Native offline proof ${java.util.UUID.randomUUID().toString().take(8)}"
        compose.onNodeWithText("Thought").performTextInput(body)
        compose.onNodeWithText("Save", useUnmergedTree = true).performScrollTo().performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("Saved to Inbox").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText("Inbox", useUnmergedTree = true).onLast().performClick()
        compose.onNodeWithText(body).performClick()
        compose.onNodeWithText("Task", useUnmergedTree = true).performScrollTo().performClick()
        compose.onNodeWithContentDescription("Edit captured thought").assert(hasText(body))
        compose.waitUntil(5000) { compose.onAllNodesWithText("Schedule · Choose date").fetchSemanticsNodes().isNotEmpty() }
        val repo = AppGraph.repository(compose.activity)
        val captureId = kotlinx.coroutines.runBlocking { repo.db.records().byKinds(repo.accountId, listOf("capture")).map { app.nook.data.wireJson.decodeFromString<app.nook.data.Record>(it.json) }.single { it.data["body"] == kotlinx.serialization.json.JsonPrimitive(body) }.id }
        val todayDate = java.time.LocalDate.now().toString()
        compose.onNodeWithText("Schedule · Choose date").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithText("Set date").assertExists()
        compose.onAllNodesWithText("Today").onLast().performClick()
        compose.onNodeWithText("Set date").performClick()
        compose.onNodeWithText("Schedule · Choose date").assertDoesNotExist()
        compose.waitUntil(5000) { kotlinx.coroutines.runBlocking { repo.get(captureId)?.data?.get("clarificationDraft")?.let { app.nook.data.wireJson.decodeFromJsonElement(app.nook.data.ClarificationDraft.serializer(), it).doDate } == todayDate } }
        compose.onAllNodesWithText("Schedule ·", substring = true).assertCountEquals(1)
        if(compose.onAllNodesWithText("Save & next", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
            compose.onNodeWithText("Save & next", useUnmergedTree = true).performScrollTo().performClick()
        else compose.onNodeWithText("Save", useUnmergedTree = true).performScrollTo().performClick()
        compose.waitUntil(5000) { kotlinx.coroutines.runBlocking {
            val records = repo.db.records().byKinds(repo.accountId, listOf("task", "capture")).map { app.nook.data.wireJson.decodeFromString<app.nook.data.Record>(it.json) }
            records.any { it.kind == "task" && it.data["title"] == kotlinx.serialization.json.JsonPrimitive(body) && it.data["doDate"] == kotlinx.serialization.json.JsonPrimitive(java.time.LocalDate.now().toString()) } &&
                records.any { it.kind == "capture" && it.data["body"] == kotlinx.serialization.json.JsonPrimitive(body) && it.data["processedAt"] != null }
        } }
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Library", useUnmergedTree = true).performClick()
        compose.onNodeWithText("History", useUnmergedTree = true).performScrollTo().performClick()
        compose.onNodeWithText(body).performScrollTo().performClick()
        compose.onNode(hasText("Task · $body", substring = true) and hasClickAction()).performScrollTo().performClick()
        compose.onNodeWithText("Title").assert(hasText(body))
        compose.onAllNodesWithText("Schedule ·", substring = true).assertCountEquals(1)
        compose.onNodeWithText("Weekly", useUnmergedTree = true).performScrollTo().performClick()
        compose.onNodeWithText("Save repeat schedule", useUnmergedTree = true).performScrollTo().performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("Repeat schedule saved").fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(isToggleable() and hasAnySibling(hasText("Completed"))).performScrollTo().performClick()
        compose.onNodeWithText("Save", useUnmergedTree = true).performScrollTo().performClick()
        compose.waitUntil(5000) {
            kotlinx.coroutines.runBlocking {
                val repo = AppGraph.repository(compose.activity)
                repo.db.records().byKinds(repo.accountId, listOf("task")).map { app.nook.data.wireJson.decodeFromString<app.nook.data.Record>(it.json) }
                    .count { it.data["title"] == kotlinx.serialization.json.JsonPrimitive(body) } == 2
            }
        }
        compose.activityRule.scenario.recreate()
        compose.onNode(isToggleable() and hasAnySibling(hasText("Completed"))).assertIsOn()
    }
}
