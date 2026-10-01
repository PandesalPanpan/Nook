package app.nook

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.nook.data.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Rule
import org.junit.Test

class SearchRefreshTest {
    @get:Rule val compose = createComposeRule()
    @Test fun openSearchRefreshesForContextRenameAndPermanentDeletion() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), NookDatabase::class.java).build()
        val repo = NookRepository(db, "local:search-refresh", "client")
        try {
            val project = repo.create("project", wireJson.encodeToJsonElement(Project("Garden")) as JsonObject)
            val task = repo.create("task", wireJson.encodeToJsonElement(Task("Live search proof", projectId = project.id)) as JsonObject)
            compose.setContent { NookTheme { Column { SearchScreen(repo) {} } } }
            compose.onNodeWithText("Search your Nook").performTextInput("project:Garden proof")
            compose.waitUntil(5000) { compose.onAllNodesWithText("Live search proof").fetchSemanticsNodes().isNotEmpty() }
            repo.update(project.id) { it.copy(data = JsonObject(it.data + ("title" to JsonPrimitive("RemoteGarden")))) }
            compose.waitUntil(5000) { compose.onAllNodesWithText("Live search proof").fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithText("Search your Nook").performTextReplacement("project:RemoteGarden proof")
            compose.waitUntil(5000) { compose.onAllNodesWithText("Live search proof").fetchSemanticsNodes().isNotEmpty() }
            repo.delete(task.id)
            compose.waitUntil(5000) { compose.onAllNodesWithText("Live search proof").fetchSemanticsNodes().isEmpty() }
        } finally { db.close() }
    }
}
