package app.nook

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.nook.data.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class AccountSwitchFlowTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun switchingNamespaceDiscardsThePreviousEditorDraft() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        runBlocking { AppGraph.initializeFirebase(context) }
        val accounts = requireNotNull(AppGraph.firebase.value.auth).accounts
        val originalAccount = accounts.state.value.repository.accountId
        val aliceId = "instrument-alice-${UUID.randomUUID()}"
        val bobId = "instrument-bob-${UUID.randomUUID()}"
        context.getSharedPreferences("nook-local", Context.MODE_PRIVATE).edit().putBoolean("onboarded", true).commit()
        try {
            runBlocking { accounts.activate(aliceId) }
            val alice = AppGraph.repository(context)
            val note = runBlocking { alice.create("note", wireJson.encodeToJsonElement(Note("Private Alice", "Original Alice body")) as JsonObject) }
            val intent = Intent(context, MainActivity::class.java).putExtra("recordId", note.id)
            ActivityScenario.launch<MainActivity>(intent).use {
                compose.waitUntil(5000) { compose.onAllNodesWithText("Edit note").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText("Edit note").performScrollTo().performClick()
                compose.waitUntil(5000) { compose.onAllNodesWithTag("note-body-editor").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("note-body-editor").performScrollTo().performTextReplacement("Unsaved Alice draft")
                runBlocking { accounts.activate(bobId) }
                compose.waitUntil(5000) { compose.onAllNodesWithTag("note-body-editor").fetchSemanticsNodes().isEmpty() }
                assertEquals(bobId, AppGraph.repository(context).accountId)
                assertNull(runBlocking { AppGraph.repository(context).get(note.id) })
                runBlocking { accounts.activate(aliceId) }
                compose.waitUntil(5000) { compose.onAllNodesWithText("Edit note").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText("Edit note").performScrollTo().performClick()
                compose.waitUntil(5000) { compose.onAllNodesWithTag("note-body-editor").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("note-body-editor").assert(hasText("Original Alice body"))
                assertEquals("Original Alice body", runBlocking { alice.get(note.id) }!!.data["body"]!!.jsonPrimitive.content)
            }
        } finally { runBlocking { accounts.activate(originalAccount.takeUnless { it.startsWith("local:") }) } }
    }
}
