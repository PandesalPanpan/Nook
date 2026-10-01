package app.nook

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.Manifest
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import app.nook.data.*
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import androidx.work.WorkManager
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.await
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import app.nook.integration.CaptureActivity
import app.nook.integration.ReminderWorker
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.util.UUID
import java.util.concurrent.TimeUnit

class NativeAccountFlowTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun configuredShareStartupAutomaticallySyncsAndKeepsAccountControlsIsolated() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("configuredAuth") == "true")
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("nook-local", Context.MODE_PRIVATE).edit().putBoolean("onboarded", true).commit()
        val preferences = AppGraph.accounts(context)
        val staleId = "stale-account-${UUID.randomUUID()}"
        preferences.activate(staleId)
        val stale = AppGraph.repository(context)
        val privateCapture = runBlocking { stale.capture("Private stale namespace") }
        val sharedBody = "Native controls guest ${UUID.randomUUID()}"
        ActivityScenario.launch<CaptureActivity>(Intent(context, CaptureActivity::class.java).setAction(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, sharedBody)).use {
            compose.waitUntil(15_000) { AppGraph.firebase.value.ready }
            assertNotNull(AppGraph.firebase.value.auth)
            compose.waitUntil(5000) { compose.onAllNodesWithText("Save", substring = false).filter(isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Save", substring = false).assertIsEnabled().performClick()
            compose.waitUntil(5_000) { runBlocking { AppGraph.repository(context).db.records().byKinds(preferences.guestAccountId, listOf("capture")).isNotEmpty() } }
        }
        val guest = AppGraph.repository(context)
        assertEquals(preferences.guestAccountId, guest.accountId)
        assertNull(runBlocking { guest.get(privateCapture.id) })
        assertNotNull(runBlocking { stale.get(privateCapture.id) })
        val thought = runBlocking { guest.db.records().byKinds(guest.accountId, listOf("capture")).map { it.decode() }.single { it.data["body"]?.jsonPrimitive?.content == sharedBody } }
        val email = "native-ui-${UUID.randomUUID()}@example.com"
        val password = "test-only-native-password"
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java).putExtra("page", "Sync")).use { activity ->
            compose.waitUntil(15_000) { AppGraph.firebase.value.ready }
            val auth = requireNotNull(AppGraph.firebase.value.auth) { "Configured bootstrap failed: ${AppGraph.firebase.value.error}" }
            val controls = requireNotNull(AppGraph.firebase.value.actions)
            compose.onNodeWithText("Use email").performScrollTo().performClick()
            compose.onNodeWithText("Email").performScrollTo().performTextInput(email)
            compose.onNodeWithText("Password").performScrollTo().performTextInput(password)
            compose.onNodeWithText("Sign in", substring = false).performScrollTo().performClick()
            compose.waitUntil(15_000) { controls.state.value.error != null && !controls.state.value.busy }
            assertNull(auth.identity()); assertNotNull(runBlocking { guest.get(thought.id) })
            compose.onNodeWithText("New here? Create an account").performScrollTo().performClick()
            compose.onNodeWithText("Password").performScrollTo().performTextInput(password)
            compose.onNodeWithText("Create account", substring = false).performScrollTo().performClick()
            compose.waitUntil(15_000) { auth.identity() != null && !controls.state.value.busy }
            val uid = auth.identity()!!.uid
            assertEquals(uid, AppGraph.repository(context).accountId)
            assertNotNull(runBlocking { AppGraph.repository(context).get(thought.id) })
            val cloud = AppGraph.repository(context)
            // This initial upload must finish before any manual Sync action is invoked.
            compose.waitUntil(30_000) { runBlocking { cloud.db.records().operation(uid, thought.id) == null } }
            val firestore = FirebaseFirestore.getInstance(FirebaseApp.getInstance("nook"))
            assertTrue(runBlocking { firestore.document("users/$uid/records/${thought.id}").get(Source.SERVER).await().exists() })
            // Automatic WorkManager completion must be visible before invoking manual sync.
            val automaticStatus = runBlocking { withTimeout(30_000) {
                app.nook.data.observeBackgroundSync(context, uid).first { it.lastCheckedAt != null }
            } }
            assertTrue(automaticStatus.lastCheckedAt!! > 0)
            assertTrue(SyncCheckHistory(context).lastCheckedAt(uid)!! >= automaticStatus.lastCheckedAt!!)
            compose.waitUntil(30_000) { compose.onAllNodesWithText("Last checked", substring = true).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Last checked", substring = true).performScrollTo().assertIsDisplayed()
            verifyBackgroundOriginal(context,cloud,uid)
            compose.onNodeWithText("Sync now").performScrollTo().performClick()
            compose.waitUntil(15_000) { !controls.state.value.busy }
            assertNull(controls.state.value.error)
            shell("svc wifi disable"); shell("svc data disable")
            val connectivity = context.getSystemService(ConnectivityManager::class.java)
            compose.waitUntil(10_000) { connectivity.getNetworkCapabilities(connectivity.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) != true }
            val offline = runBlocking { cloud.capture("Automatic offline capture") }
            runBlocking { cloud.archive(thought.id, true) }
            assertNotNull(runBlocking { cloud.get(offline.id) })
            assertNotNull(runBlocking { cloud.db.records().operation(uid, offline.id) })
            runBlocking { withTimeout(15_000) { WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow("nook-sync-$uid").first { work -> work.any { !it.state.isFinished } } } }
            shell("svc wifi enable"); shell("svc data enable")
            compose.waitUntil(30_000) { runBlocking { cloud.db.records().due(uid, Long.MAX_VALUE).isEmpty() } }
            assertTrue(runBlocking { firestore.document("users/$uid/records/${offline.id}").get(Source.SERVER).await().exists() })
            assertTrue(runBlocking { firestore.document("users/$uid/records/${thought.id}").get(Source.SERVER).await().getBoolean("archived") == true })
            // Posted private content and deferred reminders must disappear with this namespace.
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
            val notifications = context.getSystemService(NotificationManager::class.java)
            notifications.createNotificationChannel(NotificationChannel("account-test", "Account isolation test", NotificationManager.IMPORTANCE_LOW))
            notifications.notify(88342, Notification.Builder(context, "account-test").setSmallIcon(R.drawable.ic_capture).setContentTitle("Private account reminder").build())
            compose.waitUntil(5000) { notifications.activeNotifications.any { it.id == 88342 } }
            val reminder = OneTimeWorkRequestBuilder<ReminderWorker>().setInitialDelay(1, TimeUnit.DAYS).addTag("nook-reminders-$uid").build()
            runBlocking { WorkManager.getInstance(context).enqueue(reminder).await() }
            compose.onNodeWithText("Disconnect").performScrollTo().performClick()
            compose.waitUntil(15_000) { auth.identity() == null && !controls.state.value.busy }
            assertEquals(guest.accountId, AppGraph.repository(context).accountId)
            assertNull(runBlocking { AppGraph.repository(context).get(thought.id) })
            assertTrue(notifications.activeNotifications.none { it.id == 88342 })
            assertEquals(WorkInfo.State.CANCELLED, runBlocking { WorkManager.getInstance(context).getWorkInfoByIdFlow(reminder.id).first() }!!.state)
            runBlocking { withTimeout(15_000) { WorkManager.getInstance(context).getWorkInfosByTagFlow("nook-sync").first { jobs -> jobs.none { !it.state.isFinished } } } }
            runBlocking { AppGraph.repository(context).capture("Separate local thought after disconnect") }
            compose.onNodeWithText("Use email").performScrollTo().performClick()
            compose.onNodeWithText("Email").performScrollTo().performTextInput(email)
            compose.onNodeWithText("Password").performScrollTo().performTextInput(password)
            compose.onNodeWithText("Sign in", substring = false).performScrollTo().performClick()
            compose.waitUntil(15_000) { auth.identity()?.uid == uid && !controls.state.value.busy }
            activity.recreate()
            compose.waitUntil(5_000) { compose.onAllNodesWithText(email).fetchSemanticsNodes().isNotEmpty() }
            assertEquals(uid, AppGraph.repository(context).accountId)
            assertNotNull(runBlocking { AppGraph.repository(context).get(thought.id) })
            compose.onNodeWithText("Disconnect").performScrollTo().performClick()
            compose.waitUntil(15_000) { auth.identity() == null && !controls.state.value.busy }
        }
    }
    private fun shell(command: String) {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)).use { it.readBytes() }
    }
    private fun verifyBackgroundOriginal(context: Context, repository: app.nook.data.NookRepository, uid: String) = runBlocking {
        val ready = kotlinx.coroutines.CompletableDeferred<Unit>()
        val entered = kotlinx.coroutines.CompletableDeferred<app.nook.data.FileSyncProgress>()
        val observer = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
            app.nook.data.observeBackgroundSync(context,uid).collect { status ->
                ready.complete(Unit)
                status.files?.takeIf { status.running && it.total > 0 }?.let { entered.complete(it) }
            }
        }
        try {
            withTimeout(5000) { ready.await() }
            val bytes = ByteArray(4 * 1024 * 1024) { (it % 251).toByte() }
            val started = android.os.SystemClock.elapsedRealtime()
            val capture = repository.capture("Worker original fixture","image",listOf(app.nook.data.OriginalInput("worker-original.bin","application/octet-stream",bytes)))
            val capturedAt = android.os.SystemClock.elapsedRealtime()
            // Automatic scheduling, not manual sync, must publish real WorkManager progress.
            val progress = withTimeout(30_000) { entered.await() }
            assertEquals(1,progress.total)
            val attachment = repository.db.records().byKinds(uid,listOf("attachment")).map { it.decode() }.single { !it.deleted }
            withTimeout(30_000) {
                while(repository.db.records().transfer(uid,attachment.id) == null) kotlinx.coroutines.delay(25)
            }
            val checkedAt = android.os.SystemClock.elapsedRealtime()
            assertTrue(repository.db.records().due(uid,Long.MAX_VALUE).isEmpty())
            val original = com.google.firebase.storage.FirebaseStorage.getInstance(FirebaseApp.getInstance("nook"))
                .reference.child("users/$uid/attachments/${attachment.id}/original")
            assertArrayEquals(bytes,original.getBytes(5L * 1024 * 1024).await())
            val metrics = "{\"fileBytes\":${bytes.size},\"localCaptureMs\":${capturedAt-started},\"automaticUploadMs\":${checkedAt-capturedAt},\"observedRunningFileTotal\":${progress.total}}"
            java.io.File(context.getExternalFilesDir(null),"native-file-transfer-metrics.json").writeText(metrics)
            android.util.Log.i("NookTransferMetrics",metrics)
            repository.delete(capture.id)
        } finally { observer.cancel(); observer.join() }
    }
}
