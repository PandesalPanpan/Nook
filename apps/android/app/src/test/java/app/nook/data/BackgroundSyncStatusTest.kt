package app.nook.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkInfo
import androidx.work.workDataOf
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BackgroundSyncStatusTest {
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), NookDatabase::class.java).build()
    private val repo = NookRepository(db, "local:status", "client")
    private fun work(state: WorkInfo.State, attempts: Int = 0, checked: Long = 0) = WorkInfo(
        id = java.util.UUID.randomUUID(), state = state, tags = emptySet(),
        outputData = workDataOf("lastCheckedAt" to checked), runAttemptCount = attempts)
    @After fun close() { db.close() }
    @Test fun workerProgressChoosesLatestRunningAccountJobAndDropsFinishedOrCancelledDetails() {
        val progress = FileSyncProgress(3,1,1,FileSyncPhase.UPLOADING,"original.bin",50,100)
        val older = WorkInfo(id = java.util.UUID.randomUUID(),state = WorkInfo.State.RUNNING,tags = emptySet(),
            progress = androidx.work.Data.Builder().putAll(progress.toWorkData()).putLong("fileProgressAt",10).build())
        val newerProgress = progress.copy(filename = "newer.bin",transferred = 75)
        val newer = WorkInfo(id = java.util.UUID.randomUUID(),state = WorkInfo.State.RUNNING,tags = emptySet(),
            progress = androidx.work.Data.Builder().putAll(newerProgress.toWorkData()).putLong("fileProgressAt",20).build())
        val status = backgroundSyncStatus(listOf(older),listOf(newer))
        assertEquals(newerProgress,status.files)
        assertEquals("1 of 3 files checked · 1 file needs retry · Uploading newer.bin (75%)",syncFilesText(null,status))
        val manual = AccountState(repo,syncing = true,files = progress)
        assertTrue(syncFilesText(manual,status)!!.contains("original.bin (50%)"))
        val cancelled = WorkInfo(id = older.id,state = WorkInfo.State.CANCELLED,tags = emptySet(),progress = older.progress)
        assertNull(backgroundSyncStatus(listOf(cancelled),emptyList()).files)
        assertEquals(progress,fileSyncProgress(progress.toWorkData()))
        assertNull(fileSyncProgress(androidx.work.Data.EMPTY))
    }

    @Test fun normalPeriodicWaitingIsQuietButImmediateAndRetryWorkAreVisible() {
        val periodic = work(WorkInfo.State.ENQUEUED)
        assertEquals(BackgroundSyncStatus(), backgroundSyncStatus(emptyList(), listOf(periodic)))
        assertTrue(backgroundSyncStatus(listOf(work(WorkInfo.State.BLOCKED)), listOf(periodic)).queued)
        val retry = backgroundSyncStatus(emptyList(), listOf(work(WorkInfo.State.ENQUEUED, 1)))
        assertTrue(retry.retrying)
        assertTrue(syncStatusText(null, retry).contains("Nook will retry"))
    }
    @Test fun runningWorkTakesPriorityAndCancelledWorkCannotLeakRetryStatus() {
        val status = backgroundSyncStatus(listOf(work(WorkInfo.State.CANCELLED, 3)), listOf(work(WorkInfo.State.RUNNING, 2)))
        assertTrue(status.running); assertFalse(status.retrying)
        assertEquals("Checking changes and files…", syncStatusText(AccountState(repo, syncError = "Old failure"), status))
        assertEquals(BackgroundSyncStatus(), backgroundSyncStatus(listOf(work(WorkInfo.State.CANCELLED, 3)), emptyList()))
    }
    @Test fun persistedSuccessfulWorkerCheckClearsOnlyOlderManualFailures() {
        val manual = AccountState(repo, syncError = "Manual failure", syncErrorAt = 50)
        val old = backgroundSyncStatus(listOf(work(WorkInfo.State.SUCCEEDED, checked = 20)), emptyList())
        assertEquals("Manual failure", syncStatusText(manual, old))
        val recovered = backgroundSyncStatus(listOf(work(WorkInfo.State.SUCCEEDED, checked = 100)), emptyList())
        assertEquals(100L, recovered.lastCheckedAt)
        assertTrue(syncStatusText(manual, recovered).startsWith("Last checked"))
    }
}
