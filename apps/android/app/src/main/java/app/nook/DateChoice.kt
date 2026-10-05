package app.nook

import android.app.TimePickerDialog
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Display dates in the app calendar and store local calendar days in ISO form. */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable internal fun DateChoice(label: String, value: String, change: (String) -> Unit) {
    var showCalendar by rememberSaveable(label) { mutableStateOf(false) }
    val selected = runCatching { LocalDate.parse(value) }.getOrNull()
    Column(Modifier.testTag("date-choice-$label")) {
        OutlinedButton(onClick = { showCalendar = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = RoundedCornerShape(16.dp)) {
            Text("${if(label == "Do date") "Schedule" else label} · ${selected?.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)) ?: if(label == "Deadline") "Optional" else "Choose date"}", fontSize = 13.sp)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = { change(LocalDate.now().toString()) }, modifier = Modifier.heightIn(min = 44.dp).testTag("date-choice-$label-today")) { Text("Today") }
            TextButton(onClick = { change(LocalDate.now().plusDays(1).toString()) }, modifier = Modifier.heightIn(min = 44.dp).testTag("date-choice-$label-tomorrow")) { Text("Tomorrow") }
            TextButton(onClick = { change(LocalDate.now().plusWeeks(1).toString()) }, modifier = Modifier.heightIn(min = 44.dp).testTag("date-choice-$label-next-week")) { Text("Next week") }
            if(value.isNotBlank()) TextButton(onClick = { change("") }, modifier = Modifier.heightIn(min = 44.dp)) { Text("Clear ${if(label == "Do date") "Schedule" else label}") }
        }
    }
    if(showCalendar) key(label, value) { CalendarDateDialog(label, value, { showCalendar = false }, { chosen -> change(chosen); showCalendar = false }) }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable internal fun CalendarDateDialog(label: String, value: String, dismiss: () -> Unit, choose: (String) -> Unit) {
    val current = runCatching { LocalDate.parse(value) }.getOrNull()
    val initialMillis = current?.atStartOfDay(java.time.ZoneOffset.UTC)?.toInstant()?.toEpochMilli()
    val state = rememberDatePickerState(initialSelectedDateMillis = initialMillis)
    DatePickerDialog(onDismissRequest = dismiss,
        confirmButton = { TextButton(enabled = state.selectedDateMillis != null, onClick = {
            val date = state.selectedDateMillis?.let { java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneOffset.UTC).toLocalDate().toString() }
            if(date != null) choose(date)
        }) { Text("Set date") } },
        dismissButton = { Row {
            if(value.isNotBlank()) TextButton(onClick = { choose("") }) { Text("Clear date") }
            TextButton(onClick = dismiss) { Text("Cancel") }
        } }) {
        Column(Modifier.fillMaxWidth()) {
            if(label == "Schedule" || label == "Do date") FlowRow(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                listOf("Today" to 0L, "Tomorrow" to 1L, "Next week" to 7L).forEach { (name, days) ->
                    TextButton(onClick = { state.selectedDateMillis = LocalDate.now().plusDays(days).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli() }, modifier = Modifier.heightIn(min = 44.dp)) { Text(name) }
                }
            }
            DatePicker(state, showModeToggle = false, title = { Text(if(label == "Do date") "Schedule" else label) })
        }
    }
}

@Composable internal fun TimeChoice(label: String, value: LocalTime, change: (LocalTime) -> Unit) {
    val context = LocalContext.current
    OutlinedButton(onClick = {
        TimePickerDialog(context, { _, hour, minute -> change(LocalTime.of(hour, minute)) }, value.hour, value.minute, android.text.format.DateFormat.is24HourFormat(context)).show()
    }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Text("$label · ${value.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))}")
    }
}
