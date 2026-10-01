package app.nook.integration

import app.nook.NookButton

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import app.nook.data.*
import app.nook.data.Record
import app.nook.DateChoice
import app.nook.TimeChoice
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.Instant
import kotlinx.serialization.json.*

@Composable fun RemindersScreen(repository: NookRepository, records: List<Record>, act: (String, suspend () -> Unit) -> Unit) {
    val context = LocalContext.current
    val inboxReminder = records.firstOrNull { it.kind == "reminder" && !it.archived && it.data["type"]?.jsonPrimitive?.content == "inbox" }
    val data = inboxReminder?.let { wireJson.decodeFromJsonElement(Reminder.serializer(), it.data) }
    var time by rememberSaveable { mutableStateOf(data?.let { Instant.ofEpochMilli(it.scheduledAt).atZone(ZoneId.systemDefault()).toLocalTime().toString() } ?: "20:00") }
    var skip by rememberSaveable { mutableStateOf(data?.skipIfInboxEmpty ?: true) }
    var permissionDenied by remember { mutableStateOf(false) }
    fun enable() { act("Inbox review enabled") {
        repository.setInboxReview(true, LocalTime.parse(time), skip)
        repository.db.records().all(repository.accountId).map { it.decode() }.filter { it.kind == "reminder" && !it.deleted && it.data["type"]?.jsonPrimitive?.content == "inbox" }.forEach {
            context.getSharedPreferences("nook-inbox-clocks", android.content.Context.MODE_PRIVATE).edit().putString("${repository.accountId}:${it.id}", time).apply()
        }
        scheduleSystemRefresh(context)
    } }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if(granted) enable() else permissionDenied = true }
    Text("Use reminders for remembering—not guilt.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Process Inbox", style = MaterialTheme.typography.titleLarge)
            TimeChoice("Every evening", LocalTime.parse(time)) { time = it.toString() }
            Row { Text("If Inbox is empty", Modifier.weight(1f)); Switch(skip, { skip = it }) }
            Text(if(skip) "Don’t notify" else "A gentle check-in is okay", color = MaterialTheme.colorScheme.onSurfaceVariant)
            NookButton(onClick = { if(Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS) else enable() }, shape = CircleShape) { Text("Allow notifications & save") }
            if(inboxReminder != null) TextButton(onClick = { act("Inbox review disabled") { repository.setInboxReview(false); scheduleSystemRefresh(context) } }) { Text("Turn off Inbox review") }
            if(permissionDenied) Text("Notifications are off. You can allow them in Android’s app settings.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    Text("Android may deliver reminders a little later to save battery. Do date and deadline stay separate.", color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable fun TaskReminderEditor(task: Record, records: List<Record>, repository: NookRepository, act: (String, suspend () -> Unit) -> Unit) {
    val context = LocalContext.current
    val id = task.data["reminderId"]?.jsonPrimitive?.contentOrNull
    val reminder = records.find { it.kind == "reminder" && it.id == id && !it.archived }?.let { wireJson.decodeFromJsonElement(Reminder.serializer(), it.data) }
    var whenText by rememberSaveable(task.id) { mutableStateOf(reminder?.let { Instant.ofEpochMilli(it.scheduledAt).atZone(ZoneId.systemDefault()).toLocalDateTime().toString() } ?: "") }
    var deadline by rememberSaveable(task.id) { mutableStateOf(reminder?.type == "deadline") }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if(granted) scheduleSystemRefresh(context) }
    Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Reminder", style = MaterialTheme.typography.titleLarge)
            val chosen = runCatching { LocalDateTime.parse(whenText) }.getOrNull()
            DateChoice("Reminder date", chosen?.toLocalDate()?.toString().orEmpty()) { date ->
                whenText = if(date.isBlank()) "" else java.time.LocalDate.parse(date).atTime(chosen?.toLocalTime() ?: LocalTime.of(9, 0)).toString()
            }
            if(chosen != null) TimeChoice("Reminder time", chosen.toLocalTime()) { whenText = chosen.toLocalDate().atTime(it).toString() }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { whenText = LocalDateTime.now().plusHours(3).withSecond(0).withNano(0).toString() }) { Text("Later today") }
                TextButton(onClick = { whenText = LocalDateTime.now().plusDays(1).withHour(9).withMinute(0).withSecond(0).withNano(0).toString() }) { Text("Tomorrow") }
            }
            Row { Text("Deadline reminder", Modifier.weight(1f)); Switch(deadline, { deadline = it }) }
            NookButton(onClick = { act("Reminder saved") {
                val timestamp = if(whenText.isBlank()) null else LocalDateTime.parse(whenText).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                repository.setTaskReminder(task.id, timestamp, if(deadline) "deadline" else "task"); scheduleSystemRefresh(context)
            } }, shape = CircleShape) { Text(if(whenText.isBlank()) "Remove reminder" else "Save reminder") }
            if(Build.VERSION.SDK_INT >= 33) TextButton(onClick = { permission.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text("Allow notifications") }
        }
    }
}
