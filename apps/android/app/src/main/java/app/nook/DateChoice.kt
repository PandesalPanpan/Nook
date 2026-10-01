package app.nook

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

internal fun showDatePicker(context: Context, value: String, change: (String) -> Unit) {
    val initial = runCatching { LocalDate.parse(value) }.getOrNull() ?: LocalDate.now()
    DatePickerDialog(context, { _, year, month, day -> change(LocalDate.of(year, month + 1, day).toString()) }, initial.year, initial.monthValue - 1, initial.dayOfMonth).show()
}

/** Display local dates naturally; store the existing ISO wire format. */
@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun DateChoice(label: String, value: String, change: (String) -> Unit) {
    val context = LocalContext.current
    val selected = runCatching { LocalDate.parse(value) }.getOrNull()
    Column(Modifier.testTag("date-choice-$label")) {
        OutlinedButton(onClick = {
            showDatePicker(context, value, change)
        }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text("$label · ${selected?.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)) ?: "Choose date"}")
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { change(LocalDate.now().toString()) }) { Text("Today") }
            TextButton(onClick = { change(LocalDate.now().plusDays(1).toString()) }) { Text("Tomorrow") }
            TextButton(onClick = { change(LocalDate.now().plusWeeks(1).toString()) }) { Text("Next week") }
            if(value.isNotBlank()) TextButton(onClick = { change("") }) { Text("Clear $label") }
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
