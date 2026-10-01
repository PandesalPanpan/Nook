package app.nook.integration

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.nook.data.Record
import kotlinx.serialization.json.*

private fun Record.label() = data["title"]?.jsonPrimitive?.contentOrNull.orEmpty().ifBlank { "Untitled $kind" }
@Composable fun DailyRelatedEditor(ids: List<String>, records: List<Record>, ownerId: String, change: (List<String>) -> Unit, open: (Record) -> Unit) {
    var picker by remember { mutableStateOf(false) }; var query by remember { mutableStateOf("") }
    Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp)) {
            Text("Linked today", style = MaterialTheme.typography.titleMedium)
            ids.forEach { id -> records.find { it.id == id }?.let { item ->
                Row { TextButton(onClick = { open(item) }, modifier = Modifier.weight(1f)) { Text(item.label()) }; TextButton(onClick = { change(ids - id) }) { Text("Unlink") } }
            } }
            TextButton(onClick = { picker = true }) { Text("Link related item") }
        }
    }
    if(picker) AlertDialog(onDismissRequest = { picker = false }, title = { Text("Link related item") }, text = {
        Column { OutlinedTextField(query, { query = it }, label = { Text("Search items") }, singleLine = true)
            LazyColumn(Modifier.heightIn(max = 320.dp)) { items(records.filter { it.id != ownerId && !it.deleted && it.kind in setOf("note", "task", "project", "area", "resource") && it.label().contains(query, true) }, key = { it.id }) { item ->
                TextButton(onClick = { change((ids + item.id).distinct()); picker = false }) { Text(item.label()) }
            } }
        }
    }, confirmButton = { TextButton(onClick = { picker = false }) { Text("Done") } })
}
