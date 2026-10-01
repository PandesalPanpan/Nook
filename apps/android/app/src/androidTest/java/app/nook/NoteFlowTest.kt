package app.nook

import android.content.Context
import android.content.Intent
import android.text.Spanned
import android.text.style.ClickableSpan
import android.text.TextPaint
import io.noties.markwon.core.spans.StrongEmphasisSpan
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.text.TextRange
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.nook.data.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class NoteFlowTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun moreFormattingPreservesSelectedTextAcrossPopupFocus() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("nook-local", Context.MODE_PRIVATE).edit().putBoolean("onboarded", true).commit()
        val repo = AppGraph.repository(context)
        val note = runBlocking { repo.create("note", wireJson.encodeToJsonElement(Note("Menu selection ${java.util.UUID.randomUUID()}", "one two three")) as JsonObject) }
        try { ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java).putExtra("recordId", note.id)).use {
            compose.waitUntil(5000) { compose.onAllNodesWithText("Edit note").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Edit note").performScrollTo().performClick()
            compose.onNodeWithText("Note body").performScrollTo().performTextInputSelection(TextRange(4, 7))
            compose.onNodeWithContentDescription("More formatting").performScrollTo().performClick()
            compose.onNodeWithText("Code", useUnmergedTree = true).performClick()
            compose.onNodeWithText("Note body").assert(hasText("one `two` three"))
            compose.onNodeWithContentDescription("Note actions").performScrollTo().performClick()
            compose.onNodeWithText("Save note").performClick()
            compose.waitUntil(5000) { runBlocking { repo.get(note.id)!!.data["body"]!!.jsonPrimitive.content == "one `two` three" } }
        } } finally { runBlocking { repo.delete(note.id) } }
    }
    @Test fun formatPreviewLinkAndReopenOffline() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("nook-local", Context.MODE_PRIVATE).edit().putBoolean("onboarded", true).commit()
        val repo = AppGraph.repository(context)
        val suffix = java.util.UUID.randomUUID().toString().take(8)
        val targetTitle = "Garden link target $suffix"
        val sourceTitle = "Native editor proof $suffix"
        val target = runBlocking { repo.create("note", wireJson.encodeToJsonElement(Note(targetTitle, "A destination")) as JsonObject) }
        val source = runBlocking { repo.create("note", wireJson.encodeToJsonElement(Note(sourceTitle, "selection")) as JsonObject) }
        val intent = Intent(context, MainActivity::class.java).putExtra("recordId", source.id)
        try { ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            compose.waitUntil(5000) { compose.onAllNodesWithText("Edit note").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Edit note").performScrollTo().performClick()
            compose.onNodeWithText("Note body").performScrollTo().performTextInputSelection(TextRange(0, 9))
            compose.onNodeWithContentDescription("Bold").performScrollTo().performClick()
            compose.onNodeWithText("Note body").assert(hasText("**selection**"))
            compose.onNodeWithText("Link note", useUnmergedTree = true).performScrollTo().performClick()
            compose.onNodeWithText(targetTitle, useUnmergedTree = true).performClick()
            compose.onNodeWithText("Note body").assert(hasText("**[[${target.id}]]**"))
            compose.onNodeWithText("Preview", useUnmergedTree = true).performScrollTo().performClick()
            compose.waitForIdle()
            fun markdown(view: View): TextView? {
                if(view is TextView && view.tag is io.noties.markwon.Markwon) return view
                if(view is ViewGroup) for(index in 0 until view.childCount) markdown(view.getChildAt(index))?.let { return it }
                return null
            }
            scenario.onActivity { activity ->
                val view = requireNotNull(markdown(activity.window.decorView))
                val text = view.text as Spanned
                assertTrue(text.toString().contains(targetTitle))
                val strong = text.getSpans(0, text.length, StrongEmphasisSpan::class.java).single()
                val paint = TextPaint(view.paint); strong.updateDrawState(paint)
                assertTrue(paint.isFakeBoldText || paint.typeface?.isBold == true)
                text.getSpans(0, text.length, ClickableSpan::class.java).single().onClick(view)
            }
            compose.waitUntil(5000) {
                runBlocking { repo.get(source.id)!!.data["body"]!!.jsonPrimitive.content.contains("[[${target.id}]]") } &&
                    compose.onAllNodes(hasContentDescription("Title") and hasText(targetTitle)).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithContentDescription("Title").assert(hasText(targetTitle))
            compose.onAllNodesWithText(sourceTitle, useUnmergedTree = true).onLast().performScrollTo().performClick()
            compose.waitUntil(5000) { compose.onAllNodes(hasContentDescription("Title") and hasText(sourceTitle)).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Edit note").performScrollTo().performClick()
            compose.waitUntil(5000) { compose.onAllNodesWithText("Note body").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Note body").assert(hasText("**[[${target.id}]]**"))
            scenario.recreate()
            compose.waitUntil(5000) { compose.onAllNodes(hasContentDescription("Title") and hasText(sourceTitle)).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Edit note").performScrollTo().performClick()
            compose.onNodeWithText("Note body").assert(hasText("**[[${target.id}]]**"))
        } } finally { runBlocking { repo.delete(source.id); repo.delete(target.id) } }
    }
}
