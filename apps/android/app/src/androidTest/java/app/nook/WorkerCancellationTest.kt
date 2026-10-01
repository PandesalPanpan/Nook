package app.nook

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.ExistingWorkPolicy
import androidx.work.await
import app.nook.data.SyncWorker
import app.nook.data.cancelSyncAndWait
import app.nook.data.scheduleSync
import app.nook.data.schedulePeriodicSync
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit
import java.util.UUID

class WorkerCancellationTest {
    @Test fun repeatedChangesKeepOneQueuedSyncAndOnePeriodicRequest() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = WorkManager.getInstance(context)
        val uid = "queue-test-${UUID.randomUUID()}"
        val held = OneTimeWorkRequestBuilder<SyncWorker>().setInitialDelay(1, TimeUnit.DAYS).addTag("nook-sync").build()
        try {
            manager.enqueueUniqueWork("nook-sync-$uid", ExistingWorkPolicy.APPEND_OR_REPLACE, held).await()
            repeat(5) { scheduleSync(context, uid) }
            val queued = manager.getWorkInfosForUniqueWorkFlow("nook-sync-$uid").first()
            assertEquals(listOf(held.id), queued.filter { !it.state.isFinished }.map { it.id })
            schedulePeriodicSync(context, uid); schedulePeriodicSync(context, uid)
            val periodic = manager.getWorkInfosForUniqueWorkFlow("nook-sync-periodic-$uid").first()
            assertEquals(1, periodic.count { !it.state.isFinished })
            cancelSyncAndWait(context)
            assertTrue(manager.getWorkInfosForUniqueWorkFlow("nook-sync-$uid").first().all { it.state == WorkInfo.State.CANCELLED })
            assertTrue(manager.getWorkInfosForUniqueWorkFlow("nook-sync-periodic-$uid").first().all { it.state == WorkInfo.State.CANCELLED })
        } finally { manager.cancelUniqueWork("nook-sync-$uid").await(); manager.cancelUniqueWork("nook-sync-periodic-$uid").await() }
    }
    @Test fun credentialBarrierCancelsSyncWorkWithoutCancellingOtherWork() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = WorkManager.getInstance(context)
        val sync = OneTimeWorkRequestBuilder<SyncWorker>().setInitialDelay(1, TimeUnit.DAYS).addTag("nook-sync").build()
        val other = OneTimeWorkRequestBuilder<SyncWorker>().setInitialDelay(1, TimeUnit.DAYS).build()
        try {
            manager.enqueue(listOf(sync, other)).await()
            cancelSyncAndWait(context)
            assertEquals(WorkInfo.State.CANCELLED, manager.getWorkInfoByIdFlow(sync.id).first()!!.state)
            assertEquals(WorkInfo.State.ENQUEUED, manager.getWorkInfoByIdFlow(other.id).first()!!.state)
        } finally { manager.cancelWorkById(other.id).await() }
    }
}
