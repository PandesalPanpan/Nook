package app.nook.integration

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.room.withTransaction
import androidx.work.*
import app.nook.AppGraph
import app.nook.MainActivity
import app.nook.R
import app.nook.data.*
import app.nook.data.Record
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement

private const val CHANNEL = "nook-reminders"
private fun Record.reminder() = wireJson.decodeFromJsonElement(Reminder.serializer(), data)
private fun workName(account: String, id: String) = "nook-reminder:$account:$id"
private fun notificationId(account: String, id: String) = "$account:$id".hashCode()

/** Pure policy: stale, deleted, completed and empty-Inbox work never creates a notification. */
fun reminderMessage(reminder: Record, records: List<Record>): Pair<String, String>? {
    if(reminder.kind != "reminder" || reminder.deleted || reminder.archived) return null
    val data = reminder.reminder()
    val live = records.filter { it.accountId == reminder.accountId && !it.deleted && !it.archived }
    if(data.type == "inbox") {
        val count = live.count { it.kind == "capture" }
        if(count == 0 && data.skipIfInboxEmpty) return null
        return "Anything on your mind?" to if(count == 0) "A little room to think. Capture when ready." else "${if(count == 1) "1 thing is" else "$count things are"} waiting in Inbox. A two-minute review is enough."
    }
    val target = live.find { it.id == data.targetId && it.kind == "task" } ?: return null
    val task = wireJson.decodeFromJsonElement(Task.serializer(), target.data)
    if(task.completed) return null
    return task.title to if(data.type == "deadline") "Deadline reminder" else "A gentle reminder"
}

suspend fun NookRepository.setTaskReminder(taskId: String, scheduledAt: Long?, type: String = "task") = db.withTransaction {
    val record = requireNotNull(get(taskId)); require(record.kind == "task" && !record.deleted)
    val task = wireJson.decodeFromJsonElement(Task.serializer(), record.data)
    if(scheduledAt == null) {
        task.reminderId?.let { id -> get(id)?.takeUnless { it.deleted }?.let { delete(id) } }
        update(taskId) { it.copy(data = wireJson.encodeToJsonElement(task.copy(reminderId = null)) as JsonObject) }
    } else {
        require(scheduledAt >= 0 && type in setOf("task", "deadline"))
        val payload = Reminder(taskId, scheduledAt, type)
        val existing = task.reminderId?.let { get(it) }?.takeUnless { it.deleted }
        val reminder = if(existing != null) update(existing.id) { it.copy(archived = false, data = wireJson.encodeToJsonElement(payload) as JsonObject) }
            else create("reminder", wireJson.encodeToJsonElement(payload) as JsonObject)
        update(taskId) { it.copy(data = wireJson.encodeToJsonElement(task.copy(reminderId = reminder.id)) as JsonObject) }
    }
}

suspend fun NookRepository.setInboxReview(enabled: Boolean, time: LocalTime = LocalTime.of(20, 0), skipEmpty: Boolean = true) = db.withTransaction {
    val all = db.records().all(accountId).map { it.decode() }.filter { !it.deleted }
    val existing = all.filter { it.kind == "reminder" && it.reminder().type == "inbox" }
    updateSettings { it.copy(inboxReviewEnabled = enabled) }
    if(!enabled) existing.filter { !it.archived }.forEach { archive(it.id, true) }
    else {
        val zone = ZoneId.systemDefault(); val now = Instant.now()
        var next = LocalDate.now().atTime(time).atZone(zone)
        if(!next.toInstant().isAfter(now)) next = next.plusDays(1)
        val reminder = Reminder(scheduledAt = next.toInstant().toEpochMilli(), type = "inbox", skipIfInboxEmpty = skipEmpty)
        val first = existing.firstOrNull()
        if(first == null) create("reminder", wireJson.encodeToJsonElement(reminder) as JsonObject)
        else update(first.id) { it.copy(archived = false, data = wireJson.encodeToJsonElement(reminder) as JsonObject) }
        existing.drop(1).filter { !it.archived }.forEach { archive(it.id, true) }
    }
}

/** Remove the previous namespace's scheduled and already-posted reminders before publishing another. */
suspend fun cancelAccountReminders(context: Context, accountId: String) {
    WorkManager.getInstance(context.applicationContext).cancelAllWorkByTag("nook-reminders-$accountId").await()
    NotificationManagerCompat.from(context).cancelAll()
}

suspend fun reconcileReminders(context: Context) {
    val repository = AppGraph.activeRepository(context)
    val all = repository.db.records().byKinds(repository.accountId, listOf("reminder", "capture", "task")).map { it.decode() }
    val work = WorkManager.getInstance(context)
    all.filter { it.kind == "reminder" }.forEach { reminder ->
        val data = reminder.reminder()
        val name = workName(repository.accountId, reminder.id)
        val targetGone = data.type != "inbox" && reminderMessage(reminder.copy(archived = false), all) == null
        if(reminder.deleted || reminder.archived || targetGone) {
            work.cancelUniqueWork(name)
            if(reminder.deleted || targetGone) NotificationManagerCompat.from(context).cancel(notificationId(repository.accountId, reminder.id))
        } else {
            val request = OneTimeWorkRequestBuilder<ReminderWorker>().setInputData(workDataOf("accountId" to repository.accountId, "reminderId" to reminder.id, "version" to reminder.updatedAt))
                .setInitialDelay(maxOf(0, data.scheduledAt - System.currentTimeMillis()), TimeUnit.MILLISECONDS).addTag("nook-reminders-${repository.accountId}").build()
            // Absolute due time keeps edits/restarts from drifting; replacement is durable in WorkManager.
            work.enqueueUniqueWork(name, ExistingWorkPolicy.REPLACE, request)
        }
    }
}

class ReminderWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val repository = AppGraph.activeRepository(applicationContext)
        if(inputData.getString("accountId") != repository.accountId) return Result.success()
        val id = inputData.getString("reminderId") ?: return Result.failure()
        val reminder = repository.get(id) ?: return Result.success()
        if(reminder.deleted || reminder.archived || reminder.updatedAt != inputData.getLong("version", -1)) return Result.success()
        val data = reminder.reminder()
        if(data.scheduledAt > System.currentTimeMillis()) return Result.retry()
        val all = repository.db.records().byKinds(repository.accountId, listOf("reminder", "capture", "task")).map { it.decode() }
        val message = reminderMessage(reminder, all)
        if(message != null) {
            if(Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return Result.success()
            if(!NotificationManagerCompat.from(applicationContext).areNotificationsEnabled()) return Result.success()
            val manager = applicationContext.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Gentle reminders", NotificationManager.IMPORTANCE_DEFAULT))
            fun activity(intent: Intent, action: String): PendingIntent = PendingIntent.getActivity(applicationContext, notificationId(repository.accountId, "$id:$action"), intent.setData(android.net.Uri.parse("nook://reminder/$id/$action")), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val process = activity(Intent(applicationContext, MainActivity::class.java).putExtra("page", if(data.type == "inbox") "Inbox" else "Projects").putExtra("recordId", data.targetId), "process")
            val capture = activity(Intent(applicationContext, CaptureActivity::class.java), "capture")
            val later = PendingIntent.getBroadcast(applicationContext, notificationId(repository.accountId, id), Intent(applicationContext, LaterReceiver::class.java).putExtra("accountId", repository.accountId).putExtra("reminderId", id), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val notification = NotificationCompat.Builder(applicationContext, CHANNEL).setSmallIcon(R.drawable.ic_capture).setContentTitle(message.first).setContentText(message.second)
                .setStyle(NotificationCompat.BigTextStyle().bigText(message.second)).setContentIntent(process).setAutoCancel(true).setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .addAction(0, "Capture", capture).addAction(0, if(data.type == "inbox") "Process" else "Open", process).addAction(0, "Later", later).build()
            if(AppGraph.activeRepository(applicationContext).accountId != repository.accountId) return Result.success()
            NotificationManagerCompat.from(applicationContext).notify(notificationId(repository.accountId, id), notification)
        }
        if(data.type == "inbox") {
            val clock = applicationContext.getSharedPreferences("nook-inbox-clocks", Context.MODE_PRIVATE).getString("${repository.accountId}:$id", null)?.let { LocalTime.parse(it) }
                ?: Instant.ofEpochMilli(data.scheduledAt).atZone(ZoneId.systemDefault()).toLocalTime()
            var next = LocalDate.now().atTime(clock).atZone(ZoneId.systemDefault())
            if(next.toInstant().toEpochMilli() <= System.currentTimeMillis()) next = next.plusDays(1)
            repository.update(id) { it.copy(data = wireJson.encodeToJsonElement(data.copy(scheduledAt = next.toInstant().toEpochMilli())) as JsonObject) }
        } else repository.archive(id, true)
        scheduleSystemRefresh(applicationContext)
        return Result.success()
    }
}

class LaterReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val repository = AppGraph.activeRepository(context)
                if(intent.getStringExtra("accountId") != repository.accountId) return@launch
                val id = intent.getStringExtra("reminderId") ?: return@launch
                val reminder = repository.get(id)?.takeUnless { it.deleted } ?: return@launch
                val all = repository.db.records().byKinds(repository.accountId, listOf("reminder", "capture", "task")).map { it.decode() }
                if(reminderMessage(reminder.copy(archived = false), all) == null) return@launch
                if(reminder.reminder().type == "inbox") {
                    val clocks = context.getSharedPreferences("nook-inbox-clocks", Context.MODE_PRIVATE)
                    val key = "${repository.accountId}:$id"
                    if(!clocks.contains(key)) clocks.edit().putString(key, Instant.ofEpochMilli(reminder.reminder().scheduledAt).atZone(ZoneId.systemDefault()).toLocalTime().toString()).apply()
                }
                repository.update(id) { it.copy(archived = false, data = wireJson.encodeToJsonElement(reminder.reminder().copy(scheduledAt = System.currentTimeMillis() + 60 * 60 * 1000)) as JsonObject) }
                NotificationManagerCompat.from(context).cancel(notificationId(repository.accountId, id)); scheduleSystemRefresh(context)
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { /* A removed target or changed account invalidates this notification action. */ }
            finally { pending.finish() }
        }
    }
}
