package app.nook.integration

import app.nook.NookButton

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.nook.data.*
import app.nook.data.Record
import kotlinx.serialization.json.*
import java.time.LocalDate

@Composable fun WeeklyReviewScreen(repository: NookRepository, records: List<Record>, open: (Record) -> Unit, inbox: () -> Unit, act: (String, suspend () -> Unit) -> Unit) {
    val preferences = LocalContext.current.getSharedPreferences("nook-review", android.content.Context.MODE_PRIVATE)
    val key = "${repository.accountId}:${weekStart(LocalDate.now().toString())}"
    var step by remember(key) { mutableStateOf(preferences.getInt("$key:step", 0).coerceIn(0, 3)) }
    var focus by remember(key) { mutableStateOf(preferences.getString("$key:focus", "").orEmpty()) }
    var reviewed by remember(key) { mutableStateOf(preferences.getStringSet("$key:reviewed", emptySet()).orEmpty().toSet()) }
    fun choose(index: Int) { step = index; preferences.edit().putInt("$key:step", index).apply() }
    fun review(record: Record) { reviewed = reviewed + record.id; preferences.edit().putStringSet("$key:reviewed", reviewed).apply(); open(record) }
    val active = records.filter { !it.deleted && !it.archived }
    val captures = active.filter { it.kind == "capture" }; val projects = active.filter { it.kind == "project" }; val areas = active.filter { it.kind == "area" }
    Text("Reset the system—not yourself.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    listOf("Clear the inbox", "Review active projects", "Areas").forEachIndexed { index, heading ->
        Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("${index+1} · $heading", style = MaterialTheme.typography.titleMedium)
                Text(when(index) {0 -> "${captures.size} items waiting"; 1 -> "${projects.size} active · What’s the next visible action?"; else -> "Anything quietly slipping?"}, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if(index == 0) NookButton(onClick = inbox) { Text("Process") }
                else {
                    TextButton(onClick = { choose(index) }) { Text(if(index == 1) "Review" else "Check") }
                    if(step == index) (if(index == 1) projects else areas).forEach { record ->
                        TextButton(onClick = { review(record) }) { Text(record.data["title"]?.jsonPrimitive?.content.orEmpty() + if(record.id in reviewed) " · Reviewed" else "") }
                    }
                }
            }
        }
    }
    NookButton(onClick = { choose(3) }, modifier = Modifier.fillMaxWidth()) { Text("Finish review") }
    if(step == 3) {
        Text("Choose next week’s focus", style = MaterialTheme.typography.titleMedium)
        Text("One thing is enough.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(focus, { focus = it; preferences.edit().putString("$key:focus", it).apply() }, label = { Text("Next focus") }, modifier = Modifier.fillMaxWidth())
        NookButton(enabled = focus.isNotBlank(), onClick = { act("Focus saved locally") { open(repository.saveWeeklyFocus(LocalDate.now().toString(), focus)) } }) { Text("Save focus") }
    }
    Text("Missing a review doesn’t reset anything. Come back when the system starts feeling noisy.", color = MaterialTheme.colorScheme.onSurfaceVariant)
}
