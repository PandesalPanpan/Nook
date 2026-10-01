package app.nook

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.*
import app.nook.data.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class BackgroundSyncFlowTest {
    @Test fun workStatusIsAccountScopedAndSurvivesObserverRecreation() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = WorkManager.getInstance(context)
        val uid = "status-${java.util.UUID.randomUUID()}"
        val name = "nook-sync-$uid"
        val periodicName = "nook-sync-periodic-$uid"
        val request = OneTimeWorkRequestBuilder<SyncWorker>().setInitialDelay(1, TimeUnit.HOURS).build()
        try {
            manager.enqueueUniqueWork(name, ExistingWorkPolicy.KEEP, request).await()
            withTimeout(5000) { assertTrue(observeBackgroundSync(context, uid).first { it.queued }.queued) }
            // A fresh observer reads persisted WorkManager state, without an in-memory session attempt.
            assertTrue(withTimeout(5000) { observeBackgroundSync(context, uid).first { it.queued }.queued })
            assertEquals(BackgroundSyncStatus(), withTimeout(5000) { observeBackgroundSync(context, "other-$uid").first() })
            assertEquals(BackgroundSyncStatus(), observeBackgroundSync(context, "local:guest").first())
            manager.cancelUniqueWork(name).await()
            assertFalse(withTimeout(5000) { observeBackgroundSync(context, uid).first { !it.queued }.queued })
            val periodic = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES).setInitialDelay(1, TimeUnit.HOURS).build()
            manager.enqueueUniquePeriodicWork(periodicName, ExistingPeriodicWorkPolicy.KEEP, periodic).await()
            assertEquals(BackgroundSyncStatus(), withTimeout(5000) { observeBackgroundSync(context, uid).first() })
            // Periodic success and pruned work history must retain the account's last check.
            val checkedAt = System.currentTimeMillis()
            assertTrue(SyncCheckHistory(context).recordSuccess(uid, checkedAt))
            assertEquals(checkedAt, withTimeout(5000) { observeBackgroundSync(context, uid).first { it.lastCheckedAt == checkedAt } }.lastCheckedAt)
            assertEquals(checkedAt, SyncCheckHistory(context).lastCheckedAt(uid))
            assertTrue(SyncCheckHistory(context).recordSuccess(uid, checkedAt - 1))
            assertEquals(checkedAt, SyncCheckHistory(context).lastCheckedAt(uid))
            assertEquals(BackgroundSyncStatus(), withTimeout(5000) { observeBackgroundSync(context, "other-$uid").first() })
        } finally { manager.cancelUniqueWork(name).await(); manager.cancelUniqueWork(periodicName).await() }
    }
}
