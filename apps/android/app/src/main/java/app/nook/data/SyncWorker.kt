package app.nook.data

import android.content.Context
import androidx.work.*
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import app.nook.integration.scheduleSystemRefresh
import app.nook.AppGraph
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class SyncWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        AppGraph.initializeFirebase(applicationContext)
        val app = FirebaseApp.getApps(applicationContext).firstOrNull { it.name == "nook" } ?: return Result.success()
        val accountId = inputData.getString("accountId") ?: return Result.success()
        if (FirebaseAuth.getInstance(app).currentUser?.uid != accountId) return Result.success()
        val preferences = applicationContext.getSharedPreferences("nook-session", Context.MODE_PRIVATE)
        if (preferences.getString("accountId", null) != accountId) return Result.success()
        val clientId = AppGraph.accounts(applicationContext).clientId
        val db = AppGraph.database(applicationContext)
        return try {
            val repository = NookRepository(db, accountId, clientId)
            coroutineScope {
                // Progress never stalls SDK callbacks; retain only the newest snapshot while WorkManager commits.
                val updates = Channel<FileSyncProgress>(Channel.CONFLATED)
                val observer = launch { for(files in updates) setProgress(files.toWorkData()) }
                try {
                    SyncEngine(repository, FirebaseTransport(FirebaseFirestore.getInstance(app), accountId))
                        .observeProgress { updates.trySend(it) }.sync()
                } finally { updates.close(); observer.join() }
            }
            scheduleSystemRefresh(applicationContext)
            if (db.records().due(accountId, Long.MAX_VALUE).isNotEmpty()) return Result.retry()
            val checkedAt = System.currentTimeMillis()
            if (!SyncCheckHistory(applicationContext).recordSuccess(accountId, checkedAt)) return Result.retry()
            Result.success(workDataOf("lastCheckedAt" to checkedAt))
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { Result.retry() }
    }
}

private val scheduling = Mutex()

suspend fun scheduleSync(context: Context, accountId: String) {
    if (accountId.startsWith("local:")) return
    scheduling.withLock {
        val manager = WorkManager.getInstance(context.applicationContext)
        val name = "nook-sync-$accountId"
        val existing = manager.getWorkInfosForUniqueWorkFlow(name).first()
        // One queued successor is enough: it reads the latest durable outbox when it starts.
        if (existing.any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED }) return@withLock
        val work = OneTimeWorkRequestBuilder<SyncWorker>().setInputData(workDataOf("accountId" to accountId))
            .setConstraints(syncConstraints()).setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).addTag("nook-sync").build()
        manager.enqueueUniqueWork(name, ExistingWorkPolicy.APPEND_OR_REPLACE, work).await()
    }
}

private fun syncConstraints() = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

suspend fun schedulePeriodicSync(context: Context, accountId: String) {
    if (accountId.startsWith("local:")) return
    val work = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES).setInitialDelay(15, TimeUnit.MINUTES)
        .setInputData(workDataOf("accountId" to accountId)).setConstraints(syncConstraints())
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).addTag("nook-sync").build()
    WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork("nook-sync-periodic-$accountId", ExistingPeriodicWorkPolicy.KEEP, work).await()
}

fun cancelSync(context: Context) { WorkManager.getInstance(context).cancelAllWorkByTag("nook-sync") }

/** Finish the local WorkManager cancellation transaction before credentials change. */
suspend fun cancelSyncAndWait(context: Context) {
    WorkManager.getInstance(context.applicationContext).cancelAllWorkByTag("nook-sync").await()
    Unit
}
