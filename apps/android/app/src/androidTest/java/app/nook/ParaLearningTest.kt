package app.nook

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.nook.data.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class ParaLearningTest {
    @get:Rule val compose = createComposeRule()
    @Test fun guideDismissalSuppressionAndResetWorkWithRealLocalSettings() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context,NookDatabase::class.java).build()
        val repo = NookRepository(db,"local:${UUID.randomUUID()}","client")
        try {
            compose.setContent { NookTheme {
                val records by repo.observeAll().collectAsState(initial = emptyList())
                Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).verticalScroll(rememberScrollState()).padding(16.dp)) {
                    ParaTip(repo,records,"area")
                    ParaPreferences(repo)
                }
            } }
            compose.onNodeWithText("Areas don’t have finish lines.").assertExists()
            compose.onNodeWithText("Learn more").performScrollTo().performClick()
            compose.onNode(hasText("How Nook organizes") and hasAnyAncestor(isDialog())).assertExists()
            compose.onNodeWithText("Outcomes with a finish line").assertExists()
            java.io.File(context.getExternalFilesDir(null),"para-guide.png").outputStream().use { output -> compose.onNode(isDialog()).captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG,100,output) }
            compose.onNodeWithText("Close",useUnmergedTree = true).performClick()
            compose.onNodeWithText("Got it").performScrollTo().performClick()
            compose.waitUntil(5000) { compose.onAllNodesWithText("Areas don’t have finish lines.").fetchSemanticsNodes().isEmpty() }
            var settings = wireJson.decodeFromJsonElement(AppSettings.serializer(),currentSettings(repo.observeAll().first())!!.data)
            assertEquals(listOf("para:area"),settings.dismissedTips)
            compose.onNodeWithContentDescription("Show contextual PARA tips").performScrollTo().performClick()
            compose.waitUntil(5000) { !runBlocking { wireJson.decodeFromJsonElement(AppSettings.serializer(),currentSettings(repo.observeAll().first())!!.data).tipsEnabled } }
            compose.onNodeWithText("Reset dismissed tips").performScrollTo().performClick()
            compose.waitUntil(5000) { runBlocking { wireJson.decodeFromJsonElement(AppSettings.serializer(),currentSettings(repo.observeAll().first())!!.data).dismissedTips.isEmpty() } }
            compose.onNodeWithText("Areas don’t have finish lines.").assertDoesNotExist()
            compose.onNodeWithContentDescription("Show contextual PARA tips").performScrollTo().performClick()
            compose.waitUntil(5000) { compose.onAllNodesWithText("Areas don’t have finish lines.").fetchSemanticsNodes().isNotEmpty() }
            settings = wireJson.decodeFromJsonElement(AppSettings.serializer(),currentSettings(repo.observeAll().first())!!.data)
            assertTrue(settings.tipsEnabled);assertTrue(settings.dismissedTips.isEmpty())
            assertTrue(repo.observeAll().first().all { it.kind == "settings" })
        } finally { db.close() }
    }
}
