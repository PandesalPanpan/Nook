package app.nook.data

import android.content.Context
import androidx.work.WorkManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import androidx.work.WorkInfo

data class BackgroundSyncStatus(val running: Boolean = false, val retrying: Boolean = false, val queued: Boolean = false, val lastCheckedAt: Long? = null,
    val files: FileSyncProgress? = null)

/** Periodic work waiting for its next interval is normal, not a pending change. */
fun backgroundSyncStatus(immediate: List<WorkInfo>, periodic: List<WorkInfo>): BackgroundSyncStatus {
    val active = (immediate + periodic).filter { !it.state.isFinished }
    return BackgroundSyncStatus(
        running = active.any { it.state == WorkInfo.State.RUNNING },
        retrying = active.any { it.runAttemptCount > 0 && it.state != WorkInfo.State.RUNNING },
        queued = immediate.any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED },
        lastCheckedAt = immediate.filter { it.state == WorkInfo.State.SUCCEEDED }
            .map { it.outputData.getLong("lastCheckedAt", 0) }.filter { it > 0 }.maxOrNull(),
        files = active.filter { it.state == WorkInfo.State.RUNNING && fileSyncProgress(it.progress) != null }
            .maxByOrNull { it.progress.getLong("fileProgressAt", 0) }?.let { fileSyncProgress(it.progress) },
    )
}

fun syncStatusText(manual: AccountState?, background: BackgroundSyncStatus = BackgroundSyncStatus()): String {
    val manualError = manual?.syncError?.takeUnless { (background.lastCheckedAt ?: 0) > (manual.syncErrorAt ?: Long.MAX_VALUE) }
    val checked = listOfNotNull(manual?.lastSyncAt, background.lastCheckedAt).maxOrNull()
    return when {
        manual?.syncing == true || background.running -> "Checking changes and files…"
        background.retrying -> "Some changes or files could not sync. Your saved data stays on this device; Nook will retry."
        manualError != null -> manualError
        background.queued -> "Waiting to sync. Your saved data stays on this device."
        checked != null -> "Last checked ${java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(checked))}."
        else -> "Changes and files sync automatically when connected."
    }
}

fun observeBackgroundSync(context: Context, accountId: String): Flow<BackgroundSyncStatus> {
    if(accountId.startsWith("local:")) return flowOf(BackgroundSyncStatus())
    val manager = WorkManager.getInstance(context.applicationContext)
    return combine(manager.getWorkInfosForUniqueWorkFlow("nook-sync-$accountId"),
        manager.getWorkInfosForUniqueWorkFlow("nook-sync-periodic-$accountId"),
        SyncCheckHistory(context).observe(accountId)) { immediate, periodic, persisted ->
        val status = backgroundSyncStatus(immediate, periodic)
        status.copy(lastCheckedAt = listOfNotNull(status.lastCheckedAt, persisted).maxOrNull())
    }
}

fun syncFilesText(manual: AccountState?, background: BackgroundSyncStatus = BackgroundSyncStatus()): String? {
    val progress = when {
        manual?.syncing == true && manual.files != null -> manual.files
        background.running -> background.files
        else -> manual?.files
    } ?: return null
    if(progress.total <= 0) return null
    return fileSyncProgressText(progress, manual?.syncing == true || background.running)
}
