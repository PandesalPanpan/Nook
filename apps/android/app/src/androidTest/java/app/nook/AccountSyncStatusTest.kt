package app.nook

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import app.nook.data.*
import app.nook.integration.AccountSyncStatus
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class AccountSyncStatusTest {
    @get:Rule val compose = createComposeRule()
    @Test fun fileProgressShowsActualRoomTransferRetryAndAccountFencingAtLargeText() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context,NookDatabase::class.java).build()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); var failing = true
        var remote = emptyList<app.nook.data.Record>(); var late: ((Long) -> Unit)? = null
        val filename = "original-photo-with-a-long-readable-filename.png"
        val session = AccountSession(NookRepository(db,"local:file-status","client")) { object : SyncTransport {
            override suspend fun push(record: app.nook.data.Record) = record
            override suspend fun pull(accountId: String) = remote
            override val originals = object : OriginalTransport {
                override suspend fun upload(record: app.nook.data.Record,bytes: ByteArray) {}
                override suspend fun uploadWithProgress(record: app.nook.data.Record,bytes: ByteArray,progress: (Long) -> Unit) {
                    late = progress; progress(50); entered.complete(Unit); release.await(); if(failing) error("private detail")
                }
                override suspend fun download(record: app.nook.data.Record) = byteArrayOf()
                override suspend fun remove(record: app.nook.data.Record) {}
            }
        } }
        try {
            session.activate("alice"); val repo = session.state.value.repository
            val owner = repo.capture("Photo","image",listOf(OriginalInput(filename,"image/png",ByteArray(100))))
            remote = db.records().all("alice").map { it.decode() }
            assertNotNull(owner)
            compose.setContent { Box(Modifier.fillMaxSize().background(Color(0xff171412))) { CompositionLocalProvider(LocalDensity provides Density(1f,2f)) {
                Column(Modifier.width(280.dp).testTag("file-sync-status")) { NookTheme { val state by session.state.collectAsState(); AccountSyncStatus(state) } }
            } } }
            var syncFailure: Throwable? = null
            val job = launch(Dispatchers.Default) { syncFailure = runCatching { session.sync() }.exceptionOrNull() }
            withTimeout(5000) { entered.await() }
            compose.waitUntil(5000) { compose.onAllNodesWithText("(50%)",substring = true).fetchSemanticsNodes().isNotEmpty() }
            val node = compose.onNodeWithText("Uploading",substring = true).assertTextContains("(50%)",substring = true)
            assertTrue("Progress width: ${node.fetchSemanticsNode().boundsInRoot.width}", node.fetchSemanticsNode().boundsInRoot.width <= 281f)
            val image = compose.onNodeWithTag("file-sync-status").captureToImage().asAndroidBitmap()
            java.io.File(context.getExternalFilesDir(null),"native-file-sync-large.png").outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
            release.complete(Unit); job.join()
            assertNotNull(syncFailure)
            compose.onNodeWithText("1 of 1 files checked · 1 file needs retry").assertExists()
            late?.invoke(80)
            compose.onNodeWithText("Uploading",substring = true).assertDoesNotExist()
            failing = false; session.sync()
            compose.waitUntil(5000) { compose.onAllNodesWithText("1 of 1 files checked",substring = false).fetchSemanticsNodes().isNotEmpty() }
            session.prepareSignOut(); session.activate("bob")
            compose.waitUntil(5000) { compose.onAllNodesWithText("files checked",substring = true).fetchSemanticsNodes().isEmpty() }
        } finally { release.complete(Unit); session.prepareSignOut(); db.close() }
    }
    @Test fun automaticAttemptAnnouncesProgressSafeFailureAndRecovery() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), NookDatabase::class.java).build()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); var fail = true
        val session = AccountSession(NookRepository(db, "local:status", "client")) { object : SyncTransport {
            override suspend fun push(record: Record) = record
            override suspend fun pull(accountId: String): List<Record> {
                entered.complete(Unit); release.await(); if(fail) error("private transport detail"); return emptyList()
            }
        } }
        try {
            session.activate("alice")
            var background by mutableStateOf(BackgroundSyncStatus())
            compose.setContent { NookTheme { val state by session.state.collectAsState(); AccountSyncStatus(state, background) } }
            val job = launch(Dispatchers.Default) { assertTrue(runCatching { session.sync() }.isFailure) }
            withTimeout(5000) { entered.await() }
            compose.onNodeWithText("Checking changes and files…").assertExists()
            release.complete(Unit); job.join()
            compose.waitUntil(5000) { compose.onAllNodesWithText("Some changes or files could not sync. Your saved data stays on this device; Nook will retry.").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("private transport detail").assertDoesNotExist()
            fail = false; session.sync()
            compose.waitUntil(5000) { compose.onAllNodesWithText("Last checked", substring = true).fetchSemanticsNodes().isNotEmpty() }
            compose.runOnIdle { background = BackgroundSyncStatus(queued = true) }
            compose.onNodeWithText("Waiting to sync. Your saved data stays on this device.").assertExists()
            compose.runOnIdle { background = BackgroundSyncStatus(retrying = true) }
            compose.onNodeWithText("Some changes or files could not sync. Your saved data stays on this device; Nook will retry.").assertExists()
            compose.runOnIdle { background = BackgroundSyncStatus(running = true) }
            compose.onNodeWithText("Checking changes and files…").assertExists()
            compose.runOnIdle { background = BackgroundSyncStatus() }
            session.prepareSignOut()
            compose.waitUntil(5000) { compose.onAllNodesWithText("Last checked", substring = true).fetchSemanticsNodes().isEmpty() }
        } finally { release.complete(Unit); session.prepareSignOut(); db.close() }
    }
}
