package app.nook

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.nook.ai.*
import app.nook.data.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class AiConfirmationTest {
    @get:Rule val compose = createComposeRule()
    @Test fun renderedSuggestionsDoNotCreateTasksUntilSelectedAndConfirmed() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val account = "local:${UUID.randomUUID()}"
        val db = Room.inMemoryDatabaseBuilder(context, NookDatabase::class.java).build()
        val repository = NookRepository(db, account, "client")
        val store = AiSettingsStore(context, account)
        try {
            store.save(aiDefaults("openai").copy(apiKey = "fake-ui-key"))
            val source = repository.create("project", wireJson.encodeToJsonElement(Project("Garden", "Plant it")) as JsonObject)
            var calls = 0
            compose.setContent { NookTheme { Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).verticalScroll(rememberScrollState()).padding(16.dp)) {
                AiAssist(repository, source, listOf(source)) { config ->
                    assertEquals("fake-ui-key", config.apiKey)
                    object : AiProvider { override suspend fun suggest(request: AiRequest): AiSuggestion { calls++; return AiSuggestion.Actions(listOf("Buy seeds", "Plant seeds")) } }
                }
            } } }
            compose.waitUntil(5000) { compose.onAllNodesWithText("Suggest next actions").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Suggest next actions").performScrollTo().performClick()
            compose.waitUntil(5000) { compose.onAllNodesWithText("Add selected actions").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Add selected actions").assertIsNotEnabled()
            java.io.File(context.getExternalFilesDir(null), "ai-confirmation.png").outputStream().use { output -> compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output) }
            assertTrue(db.records().all(account).none { it.kind == "task" })
            compose.onNodeWithContentDescription("Plant seeds").performScrollTo().performClick()
            compose.onNodeWithText("Add selected actions").performScrollTo().performClick()
            compose.waitUntil(5000) { compose.onAllNodesWithText("Accepted and saved locally").fetchSemanticsNodes().isNotEmpty() }
            val tasks = db.records().all(account).map { it.decode() }.filter { it.kind == "task" }
            assertEquals(1, tasks.size); assertEquals("Plant seeds", tasks.single().data["title"]!!.jsonPrimitive.content); assertEquals(1, calls)
        } finally { store.save(AiConfiguration()); db.close() }
    }
}
