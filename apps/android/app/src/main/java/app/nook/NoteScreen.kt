package app.nook

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.nook.data.NookRepository
import app.nook.data.Record
import app.nook.integration.NoteEditor
import app.nook.integration.outgoingNotes
import kotlinx.serialization.json.*

private val NoteInter = FontFamily(Font(R.font.inter))
private fun Record.noteValue(key: String) = data[key]?.jsonPrimitive?.contentOrNull.orEmpty()
@Composable private fun NoteText(text: String, size: Int, bold: Boolean = false, color: Color = Color(0xfff5eee7), modifier: Modifier = Modifier) {
    Text(text, modifier, fontFamily = NoteInter, fontSize = size.sp, lineHeight = (size * 1.4).sp,
        fontWeight = if(bold) FontWeight.Bold else FontWeight.Normal, color = color)
}

/** Source 2:517 note hierarchy, using the existing local draft, Markdown and attachment paths. */
@Composable internal fun NoteRecordContent(record: Record, draft: JsonObject, records: List<Record>, repository: NookRepository,
    field: (String, String) -> Unit, optional: (String, String) -> Unit, save: suspend () -> Unit,
    openSaved: (Record) -> Unit, act: (String, suspend () -> Unit) -> Unit, onAttach: () -> Unit, attachmentStatus: String, attachmentBusy: Boolean) {
    fun value(key: String) = draft[key]?.jsonPrimitive?.contentOrNull.orEmpty()
    val available = remember(records, record.accountId) { records.filter { it.accountId == record.accountId && !it.deleted } }
    val notes = remember(available) { available.filter { it.kind == "note" } }
    val backlinks = remember(available, notes, record.id) { available.filter { it.kind in listOf("note", "dailyNote") && it.id != record.id && outgoingNotes(it.noteValue("body"), notes).contains(record.id) }
        .sortedWith(compareBy<Record> { it.createdAt }.thenBy { it.id }) }
    var menu by remember { mutableStateOf(false) }
    var details by rememberSaveable(record.id) { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) { Dot(); NoteText("nook", 18, true) }
        Box {
            Box(Modifier.widthIn(min = 76.dp).heightIn(min = 48.dp).clickable(role = Role.Button) { focus.clearFocus(); menu = true }
                .semantics { contentDescription = "Note actions" }, contentAlignment = Alignment.Center) {
                NoteText("•••", 12, color = Color(0xffcbbfb4), modifier = Modifier.width(76.dp).background(Color(0xff342d28), RoundedCornerShape(14.dp)).padding(horizontal = 12.dp, vertical = 5.dp))
            }
            DropdownMenu(menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Save note") }, onClick = { menu = false; act("Saved locally", save) })
                DropdownMenuItem(text = { Text("Note details") }, onClick = { menu = false; details = true })
                DropdownMenuItem(text = { Text(if(record.archived) "Restore note" else "Archive note") }, onClick = {
                    menu = false; act(if(record.archived) "Restored" else "Archived") { save(); repository.archive(record.id, !record.archived) }
                })
                DropdownMenuItem(text = { Text("Delete note") }, onClick = { menu = false; act("Deleted locally") { repository.delete(record.id) } })
            }
        }
    }
    Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
        NoteText("Note", 30, true)
        BasicTextField(value("title"), { field("title", it) }, textStyle = TextStyle(fontFamily = NoteInter, fontWeight = FontWeight.Bold,
            fontSize = 24.sp, lineHeight = 33.6.sp, color = Color(0xfff5eee7)), cursorBrush = SolidColor(Color(0xff3270e6)),
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { contentDescription = "Title" }, decorationBox = { input ->
                Box { if(value("title").isEmpty()) NoteText("Untitled note", 24, true, Color(0xff9c9187)); input() }
            })
        val explicitHomes = listOf("projectId" to "Project", "areaId" to "Area", "resourceId" to "Resource").filter { (key, _) -> value(key).isNotBlank() }
        val context = explicitHomes.singleOrNull()?.let { (key, label) -> available.find { it.id == value(key) && !it.archived && it.kind == label.lowercase() }?.let { "$label · ${it.noteValue("title")}" } }
        if(context != null) NoteText(context.uppercase(), 10, true, Color(0xffff7e1d)) else if(explicitHomes.size > 1) NoteText("MULTIPLE ASSOCIATIONS", 10, true, Color(0xffff7e1d))
    }
    NoteEditor(value("body"), { field("body", it) }, notes.filter { it.id != record.id }, openSaved,
        initialPreview = value("body").isNotBlank(), onAttach = onAttach, attachmentBusy = attachmentBusy, footer = {
            if(outgoingNotes(value("body"), notes).isNotEmpty()) NoteText("Linked notes appear as backlinks below.", 12, color = Color(0xff9c9187))
            if(backlinks.isNotEmpty()) {
                HorizontalDivider(color = Color(0xff453d37))
                NoteText("Backlinks · ${backlinks.size}", 12, true, Color(0xffcbbfb4))
                Column(Modifier.fillMaxWidth()) {
                    backlinks.forEach { source ->
                        Box(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button) { focus.clearFocus(); openSaved(source) }, contentAlignment = Alignment.CenterStart) {
                            NoteText(source.noteValue("title").ifBlank { if(source.kind == "dailyNote") "Daily note · ${source.noteValue("date")}" else "Untitled note" }, 12)
                        }
                    }
                }
            }
        })
    NoteText("Link note has its own button—no typing [[ ]] on mobile.", 10, color = Color(0xff9c9187))
    if(attachmentStatus.isNotBlank()) NoteText(attachmentStatus, 12, color = Color(0xffcbbfb4), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    NookButton(onClick = { focus.clearFocus(); act("Saved locally", save) }, shape = RoundedCornerShape(24.dp), modifier = Modifier.heightIn(min = 48.dp)) { Text("Save") }
    TextButton(onClick = { details = !details; focus.clearFocus() }, modifier = Modifier.heightIn(min = 48.dp).semantics { stateDescription = if(details) "Expanded" else "Collapsed" }) {
        Text(if(details) "Hide note details" else "Note details")
    }
    if(details) Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        RecordPrimaryHomePicker(available, record.accountId, value("projectId"), value("areaId"), value("resourceId"), repository) { project, area, resource ->
            optional("projectId", project); optional("areaId", area); optional("resourceId", resource)
        }
        NoteText("Saved on this device. Context changes are included when you save.", 12, color = Color(0xffcbbfb4))
    }
}
