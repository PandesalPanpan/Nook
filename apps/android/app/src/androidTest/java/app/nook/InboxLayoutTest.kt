package app.nook

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.nook.data.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class InboxLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test fun largeTextCaptureSavesLocallyWithoutOrganizationAtBothWidths(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, NookDatabase::class.java).build()
        val repo = NookRepository(db, "local:inbox-layout", "client")
        try {
            var width by mutableStateOf(360)
            var fontScale by mutableStateOf(2f)
            var saved = 0
            compose.setContent { NookTheme {
                val records by repo.observeAll().collectAsState(initial = emptyList())
                CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                    Column(Modifier.width(width.dp).testTag("viewport").verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        InboxContent(repo, records.filter { it.kind == "capture" && !it.deleted }, {}, { saved++ })
                    }
                }
            } }
            for (viewport in listOf(360, 280)) {
                compose.runOnIdle { width = viewport }
                val body = "Remember to review the display module after lunch at width $viewport"
                val input = compose.onNodeWithContentDescription("Inbox thought")
                input.performScrollTo().assertIsDisplayed().performTextReplacement(body)
                assertTrue("Readable editor at $viewport", input.fetchSemanticsNode().boundsInRoot.width >= 200f)
                val save = compose.onNodeWithText("Save")
                save.assertIsEnabled().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
                val parent = compose.onNodeWithTag("viewport").fetchSemanticsNode().boundsInRoot
                val target = save.fetchSemanticsNode().boundsInRoot
                assertTrue(target.left >= parent.left && target.right <= parent.right)
                java.io.File(context.getExternalFilesDir(null), "native-inbox-large-text-$viewport.png").outputStream().use {
                    compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                save.performClick()
                compose.waitUntil(5000) { runBlocking { repo.observeAll().first().any { it.kind == "capture" && it.data["body"] == JsonPrimitive(body) } } }
                input.assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
                save.assertIsNotEnabled()
            }
            compose.runOnIdle { assertEquals(2, saved); width = 360; fontScale = 1f }
            val input = compose.onNodeWithContentDescription("Inbox thought")
            input.performScrollTo()
            val save = compose.onNodeWithText("Save")
            val inputBounds = input.fetchSemanticsNode().boundsInRoot
            val saveBounds = save.fetchSemanticsNode().boundsInRoot
            assertTrue("Normal text keeps the source row", saveBounds.left >= inputBounds.right && saveBounds.top < inputBounds.bottom)
            java.io.File(context.getExternalFilesDir(null), "native-inbox-normal-text-360.png").outputStream().use {
                compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
        } finally { db.close() }
    }
}
