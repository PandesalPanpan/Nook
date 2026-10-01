package app.nook

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.nook.data.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class NoteLayoutTest {
    @get:Rule val compose = createComposeRule()
    @Test fun largeTextKeepsToolbarBacklinksAndMarkdownAccessible(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, NookDatabase::class.java).build()
        try {
            val repo = NookRepository(db, "local:note-layout", "test")
            val note = repo.create("note", wireJson.encodeToJsonElement(Note("A longer classroom research note", "## Possible uses\n\nA calm note that remains readable when text is enlarged.")) as JsonObject)
            val backlink = repo.create("note", wireJson.encodeToJsonElement(Note("Capstone power planning", "[[${note.id}]]")) as JsonObject)
            var width by mutableStateOf(360)
            var opened = ""
            var host: View? = null
            compose.setContent { NookTheme { CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                host = LocalView.current
                val records by repo.observeAll().collectAsState(initial = emptyList())
                val scope = rememberCoroutineScope()
                records.find { it.id == note.id }?.let { current ->
                    Column(Modifier.width(width.dp).height(760.dp).background(Color(0xff171412)).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        RecordScreen(current, records, repo, { opened = it.id }, { _, work -> scope.launch { work() } })
                    }
                }
            } } }
            compose.waitUntil(5000) { compose.onAllNodesWithContentDescription("Note actions").fetchSemanticsNodes().isNotEmpty() }
            fun markdown(view: View): TextView? {
                if(view is TextView && view.tag is io.noties.markwon.Markwon) return view
                if(view is ViewGroup) for(index in 0 until view.childCount) markdown(view.getChildAt(index))?.let { return it }
                return null
            }
            for(viewport in listOf(360, 280)) {
                compose.runOnIdle { width = viewport }
                compose.onNodeWithContentDescription("Note actions").performScrollTo()
                compose.runOnIdle { assertEquals(28f, requireNotNull(markdown(requireNotNull(host).rootView)).textSize, 0.01f) }
                java.io.File(context.getExternalFilesDir(null), "native-note-large-$viewport.png").outputStream().use {
                    compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                for(label in listOf("Note actions", "Bold", "Italic", "List", "Checklist", "Link note", "Attach", "More formatting")) {
                    val target = compose.onNodeWithContentDescription(label).performScrollTo().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
                    target.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
                    val bounds = target.fetchSemanticsNode().boundsInRoot
                    val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
                    assertTrue("$label remains horizontally contained", bounds.left >= root.left && bounds.right <= root.right)
                }
                val related = compose.onNode(hasText("Capstone power planning") and hasClickAction()).performScrollTo()
                related.assertHeightIsAtLeast(48.dp).assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)).performClick()
                compose.waitUntil(5000) { opened == backlink.id }
                compose.runOnIdle { opened = "" }
                compose.onNodeWithContentDescription("More formatting").performScrollTo().performClick()
                compose.onNodeWithText("Heading", useUnmergedTree = true).performClick()
                compose.onNodeWithText("Note body").assertExists()
                compose.onNodeWithText("Preview", useUnmergedTree = true).performScrollTo().performClick()
            }
        } finally { db.close() }
    }
}
