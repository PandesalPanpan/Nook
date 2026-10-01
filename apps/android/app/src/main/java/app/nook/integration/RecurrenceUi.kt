package app.nook.integration

import app.nook.NookButton

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.nook.data.*
import app.nook.data.Record
import java.time.LocalDate

@Composable fun TaskRecurrenceEditor(record: Record, records: List<Record>, repository: NookRepository, act: (String, suspend () -> Unit) -> Unit) {
    val task = wireJson.decodeFromJsonElement(Task.serializer(), record.data)
    val rule = records.find { it.id == task.recurrenceId && it.kind == "recurrence" }?.let { wireJson.decodeFromJsonElement(Recurrence.serializer(), it.data) }
    var frequency by rememberSaveable(record.id) { mutableStateOf(rule?.frequency ?: "off") }
    var interval by rememberSaveable(record.id) { mutableStateOf((rule?.interval ?: 1).toString()) }
    var anchor by rememberSaveable(record.id) { mutableStateOf(rule?.anchorDate ?: task.doDate ?: task.deadline ?: LocalDate.now().toString()) }
    Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Repeat", style = MaterialTheme.typography.titleLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("off" to "None", "daily" to "Daily", "weekly" to "Weekly", "monthly" to "Monthly").forEach { (value, label) -> FilterChip(frequency == value, { frequency = value }, { Text(label) }) }
            }
            if(frequency != "off") {
                OutlinedTextField(interval, { interval = it }, label = { Text("Every · interval") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(anchor, { anchor = it }, label = { Text("First scheduled date · YYYY-MM-DD") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("Completion keeps this entry and creates the next scheduled task. Month-end dates stay anchored to the original day.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            NookButton(enabled = !task.completed && !record.archived, onClick = { act("Repeat schedule saved") {
                repository.setRecurrence(record.id, if(frequency == "off") null else Recurrence(frequency, interval.toInt(), LocalDate.parse(anchor).toString()))
            } }) { Text("Save repeat schedule") }
        }
    }
}
