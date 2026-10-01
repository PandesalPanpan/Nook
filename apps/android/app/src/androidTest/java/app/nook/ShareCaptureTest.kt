package app.nook

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import androidx.core.content.FileProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.nook.integration.CaptureActivity
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

class ShareCaptureTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun shareUrlSavesToLocalInboxAndShortcutsAreRegistered() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repository = AppGraph.repository(context)
        val before = runBlocking { repository.db.records().all(repository.accountId).size }
        val intent = Intent(context, CaptureActivity::class.java).setAction(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "https://example.com/shared-offline")
        ActivityScenario.launch<CaptureActivity>(intent).use {
            compose.onNodeWithText("Save to Nook").assertIsDisplayed()
            compose.onNodeWithText("Thought").assert(hasText("https://example.com/shared-offline"))
            compose.onNodeWithText("Save", useUnmergedTree = true).performClick()
            compose.waitUntil(5000) { runBlocking { repository.db.records().all(repository.accountId).size > before } }
        }
        val capture = runBlocking { repository.db.records().all(repository.accountId) }.map { app.nook.data.wireJson.decodeFromString<app.nook.data.Record>(it.json) }.first { it.kind == "capture" && it.data["body"]?.jsonPrimitive?.content == "https://example.com/shared-offline" }
        assertEquals("link", capture.data["captureType"]!!.jsonPrimitive.content)
        assertEquals(setOf("thought", "task", "photo"), context.getSystemService(ShortcutManager::class.java).manifestShortcuts.map { it.id }.toSet())
    }
    @Test fun sharedPhotoSavesOriginalWithoutRequiredCaption() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repository = AppGraph.repository(context)
        val output = ByteArrayOutputStream()
        Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.rgb(255, 126, 29)) }.compress(Bitmap.CompressFormat.PNG, 100, output)
        val original = output.toByteArray()
        val folder = File(context.cacheDir, "camera").apply { mkdirs() }
        val file = File.createTempFile("shared-proof-", ".png", folder).apply { writeBytes(original) }
        val uri = FileProvider.getUriForFile(context, "app.nook.files", file)
        val before = runBlocking { repository.db.records().all(repository.accountId).size }
        val intent = Intent(context, CaptureActivity::class.java).setAction(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ActivityScenario.launch<CaptureActivity>(intent).use {
            compose.onNodeWithText("1 original file attached").assertIsDisplayed()
            compose.onNodeWithText("Save", useUnmergedTree = true).assertIsEnabled().performClick()
            compose.waitUntil(5000) { runBlocking { repository.db.records().all(repository.accountId).size >= before + 2 } }
        }
        val records = runBlocking { repository.db.records().all(repository.accountId) }.map { app.nook.data.wireJson.decodeFromString<app.nook.data.Record>(it.json) }
        val attachment = records.single { it.kind == "attachment" && it.data["filename"]!!.jsonPrimitive.content == file.name }
        val capture = records.single { it.id == attachment.data["ownerId"]!!.jsonPrimitive.content }
        assertEquals("", capture.data["body"]!!.jsonPrimitive.content)
        assertArrayEquals(original, runBlocking { repository.db.records().original(repository.accountId, attachment.id) }!!.bytes)
    }
}
