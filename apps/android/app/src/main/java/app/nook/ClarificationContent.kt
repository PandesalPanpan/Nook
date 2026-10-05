package app.nook

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.nook.data.*
import app.nook.integration.outgoingNotes
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

private val ClarifySurface = Color(0xff221d1a)
private val ClarifyRaised = Color(0xff342d28)
private val ClarifyText = Color(0xfff5eee7)
private val ClarifySecondary = Color(0xffcbbfb4)
private val ClarifyOrange = Color(0xffff7e1d)
private val ClarifyBlue = Color(0xff3270e6)
private val ClarifyTeal = Color(0xff3fae9b)

@Composable private fun Heading(value: String, size: Int = 15) { Text(value, color = ClarifyText, fontSize = size.sp, lineHeight = (size * 1.4f).sp, fontWeight = FontWeight.Bold) }
@Composable private fun Copy(value: String, modifier: Modifier = Modifier) { Text(value, modifier = modifier, color = ClarifySecondary, fontSize = 12.sp, lineHeight = 17.sp) }
@Composable private fun Action(value: String, primary: Boolean = false, click: () -> Unit) {
    TextButton(onClick = click, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), colors = ButtonDefaults.textButtonColors(contentColor = if(primary) ClarifyBlue else ClarifySecondary)) { Text(value) }
}

private fun Record.read(field: String) = data[field]?.jsonPrimitive?.contentOrNull.orEmpty()
private fun Record.hasAttachments() = data["attachmentIds"]?.jsonArray?.isNotEmpty() == true
private fun Record.clarificationTitle() = if(kind == "dailyNote") "Daily note · ${read("date")}" else read("title").ifBlank { read("body").take(100).ifBlank { kind } }
private fun firstThoughtLine(body: String) = body.lineSequence().firstOrNull()?.trim()?.take(10000).orEmpty().ifBlank { body.trim().take(10000).ifBlank { "Photo capture" } }
private fun suggestedThoughtAction(body: String) = Regex("(?im)^\\s*(?:i need to|i should|need to|remember to|don't forget to|todo:?|to do:)\\s+(.+)$").find(body)?.groupValues?.get(1)?.trim().orEmpty()
internal fun formatCaptureTime(createdAt: Long, now: Long = System.currentTimeMillis()): String {
    val elapsed = (now - createdAt).coerceAtLeast(0)
    if(elapsed < 60_000) return "just now"
    if(elapsed < 3_600_000) {
        val minutes = elapsed / 60_000
        return "$minutes ${if(minutes == 1L) "minute" else "minutes"} ago"
    }
    val formatted = java.time.Instant.ofEpochMilli(createdAt).atZone(java.time.ZoneId.systemDefault())
        .format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(java.util.Locale.getDefault()))
    val separator = formatted.lastIndexOf(", ")
    return if(separator >= 0) formatted.replaceRange(separator, separator + 2, " · ") else formatted
}

@Composable internal fun RecordPrimaryHomePicker(
    records: List<Record>, accountId: String, projectId: String, areaId: String, resourceId: String, repository: NookRepository,
    choose: (projectId: String, areaId: String, resourceId: String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var open by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var newTitle by rememberSaveable { mutableStateOf("") }
    var newKind by rememberSaveable { mutableStateOf("project") }
    var error by remember { mutableStateOf("") }
    val active = records.filter { it.accountId == accountId && !it.deleted && !it.archived && it.kind in setOf("project", "area", "resource") }
    val assigned = listOfNotNull(projectId.takeIf(String::isNotBlank)?.let { "project" to it }, areaId.takeIf(String::isNotBlank)?.let { "area" to it }, resourceId.takeIf(String::isNotBlank)?.let { "resource" to it })
    val selected = assigned.singleOrNull()?.let { (_, id) -> active.firstOrNull { it.id == id } }
    val currentLabel = when {
        assigned.isEmpty() -> "Add to…"
        assigned.size > 1 -> "Multiple associations"
        selected == null -> "Unavailable collection"
        else -> "${selected.kind.replaceFirstChar { it.uppercase() }} · ${selected.clarificationTitle()}"
    }
    OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = RoundedCornerShape(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            ChoiceMark(selected?.kind ?: "project"); Text(currentLabel, color = ClarifyText, modifier = Modifier.weight(1f)); Text("›", color = ClarifySecondary)
        }
    }
    if(open) AlertDialog(onDismissRequest = { open = false }, title = { Text("Add to a Project, Area, or Resource", color = ClarifyText) },
        text = { Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            OutlinedTextField(query, { query = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Search collections") }, shape = RoundedCornerShape(14.dp), singleLine = true)
            TextButton(onClick = { choose("", "", ""); open = false }, modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)) { Text("No primary home", color = ClarifySecondary) }
            listOf("project" to "Projects", "area" to "Areas", "resource" to "Resources").forEach { (kind, heading) ->
                Heading(heading, 13)
                val group = active.filter { it.kind == kind && it.clarificationTitle().contains(query, ignoreCase = true) }
                group.forEach { item -> TextButton(onClick = { choose(if(kind == "project") item.id else "", if(kind == "area") item.id else "", if(kind == "resource") item.id else ""); open = false }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) { ChoiceMark(kind); Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) { Text(item.clarificationTitle(), color = ClarifyText); Copy(kind.replaceFirstChar { it.uppercase() }) }; if(item.id in assigned.map { it.second }) Text("✓", color = ClarifyOrange) }
                } }
                if(group.isEmpty()) Copy("No ${kind}s yet.")
            }
            HorizontalDivider(color = Color(0xff453d37)); Heading("Create a Project, Area, or Resource", 13)
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) { listOf("project", "area", "resource").forEach { kind -> FilterChip(selected = newKind == kind, onClick = { newKind = kind }, label = { Text(kind.replaceFirstChar { it.uppercase() }) }) } }
            OutlinedTextField(newTitle, { newTitle = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Name") }, shape = RoundedCornerShape(14.dp), singleLine = true)
            if(error.isNotBlank()) Text(error, color = Color(0xffff8c88))
            TextButton(enabled = newTitle.isNotBlank(), onClick = { scope.launch {
                try {
                    val created = when(newKind) {
                        "project" -> repository.create("project", wireJson.encodeToJsonElement(Project(newTitle.trim())) as JsonObject)
                        "area" -> repository.create("area", wireJson.encodeToJsonElement(Area(newTitle.trim())) as JsonObject)
                        else -> repository.create("resource", wireJson.encodeToJsonElement(Resource(newTitle.trim())) as JsonObject)
                    }
                    choose(if(newKind == "project") created.id else "", if(newKind == "area") created.id else "", if(newKind == "resource") created.id else ""); open = false
                } catch(failure: Exception) { error = failure.message ?: "Could not create this collection" }
            } }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Create ${newKind.replaceFirstChar { it.uppercase() }}", color = ClarifyOrange) }
        } }, confirmButton = { TextButton(onClick = { open = false }) { Text("Close") } })
}

@Composable internal fun RelatedItemsPanel(
    record: Record, records: List<Record>, repository: NookRepository, explicitIds: List<String>,
    change: (List<String>) -> Unit, open: (Record) -> Unit,
) {
    var picker by rememberSaveable(record.id) { mutableStateOf(false) }
    val live = records.filter { it.accountId == record.accountId && !it.deleted && !it.archived }
    val allowedKinds = setOf("task", "note", "dailyNote", "project", "area", "resource")
    val direct = explicitIds.toSet()
    val incoming = live.filter { other -> other.id != record.id && other.kind in allowedKinds &&
        other.data["relatedIds"]?.jsonArray?.any { it.jsonPrimitive.contentOrNull == record.id } == true }
    val sourceCapture = record.read("sourceCaptureId")
    val siblings = if(sourceCapture.isBlank()) emptyList() else live.filter { other -> other.id != record.id && other.kind in setOf("task", "note") && other.read("sourceCaptureId") == sourceCapture }
    val notes = live.filter { it.kind == "note" }
    val wikiIds = if(record.kind == "note") {
        outgoingNotes(record.read("body"), notes).toSet() + live.filter { it.kind in setOf("note", "dailyNote") && it.id != record.id && outgoingNotes(it.read("body"), notes).contains(record.id) }.map { it.id }
    } else emptySet()
    val linkedIds = (direct + incoming.map { it.id } + siblings.map { it.id } + wikiIds) - record.id
    val connected = linkedIds.mapNotNull { id -> live.firstOrNull { it.id == id } }.distinctBy { it.id }.sortedBy { it.clarificationTitle().lowercase() }
    Column(Modifier.fillMaxWidth().background(ClarifySurface, RoundedCornerShape(18.dp)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Heading("Connected items", 15)
            if(record.kind == "task" || record.kind == "note") TextButton(onClick = { picker = true }, modifier = Modifier.heightIn(min = 44.dp)) { Text("Connect", color = ClarifyOrange) }
        }
        connected.forEach { target -> TextButton(onClick = { open(target) }, modifier = Modifier.fillMaxWidth().heightIn(min = 46.dp)) { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) { ChoiceMark(target.kind); Column(horizontalAlignment = Alignment.Start) { Text(target.clarificationTitle(), color = ClarifyText); Copy(if(target.id in wikiIds) "Markdown link" else target.kind.replaceFirstChar { it.uppercase() }) }; Text("›", color = ClarifySecondary) } } }
        explicitIds.filter { id -> records.none { it.id == id && !it.deleted } }.forEach { missing ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Copy("Unavailable item"); TextButton(onClick = { change(explicitIds - missing) }) { Text("Remove", color = ClarifySecondary) } }
        }
        if(connected.isEmpty() && explicitIds.none { id -> records.none { it.id == id && !it.deleted } }) Copy("No connected items yet.")
    }
    if(picker) {
        var search by rememberSaveable(record.id) { mutableStateOf("") }
        var pending by rememberSaveable(record.id) { mutableStateOf(explicitIds.joinToString(",")) }
        val pendingIds = pending.split(',').filter(String::isNotBlank)
        val options = live.filter { it.id != record.id && it.kind in allowedKinds && it.clarificationTitle().contains(search, ignoreCase = true) }.sortedBy { it.clarificationTitle().lowercase() }
        AlertDialog(onDismissRequest = { picker = false }, title = { Text("Connect related items", color = ClarifyText) },
            text = { Column(Modifier.heightIn(max = 490.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedTextField(search, { search = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Search Notes, Tasks, or collections") }, shape = RoundedCornerShape(14.dp), singleLine = true)
                options.forEach { item -> Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(item.id in pendingIds, onCheckedChange = { checked -> pending = (if(checked) pendingIds + item.id else pendingIds - item.id).distinct().joinToString(",") })
                    Column(Modifier.weight(1f)) { Text(item.clarificationTitle(), color = ClarifyText); Copy(item.kind.replaceFirstChar { it.uppercase() }) }
                } }
                if(options.isEmpty()) Copy("No matching active items.")
            } }, confirmButton = { TextButton(onClick = { change(pendingIds); picker = false }) { Text("Save connections") } }, dismissButton = { TextButton(onClick = { picker = false }) { Text("Cancel") } })
    }
}

@Composable private fun ChoiceMark(kind: String, modifier: Modifier = Modifier) {
    val (symbol, tint) = when(kind) {
        "task" -> "☑" to ClarifyBlue
        "note" -> "▤" to ClarifyTeal
        "split" -> "↔" to ClarifyOrange
        "project" -> "▰" to ClarifyOrange
        "area" -> "▦" to ClarifyTeal
        "resource" -> "▣" to Color(0xffb6a8ff)
        "archive" -> "▱" to ClarifySecondary
        else -> "⌁" to ClarifySecondary
    }
    Text(symbol, modifier.semantics { contentDescription = "$kind icon" }, color = tint, fontSize = 20.sp, fontWeight = FontWeight.Bold)
}

@Composable internal fun ClarificationContent(
    capture: Record,
    captures: List<Record>,
    records: List<Record>,
    repository: NookRepository,
    saved: (String?) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val saveMutex = remember(capture.id) { Mutex() }
    val storedDraft = remember(capture.id) { runCatching { capture.data["clarificationDraft"]?.let { wireJson.decodeFromJsonElement(ClarificationDraft.serializer(), it) } }.getOrNull() }
    var thought by rememberSaveable(capture.id) { mutableStateOf(capture.read("body")) }
    var mode by rememberSaveable(capture.id) { mutableStateOf(storedDraft?.mode ?: if(capture.read("captureType") == "task") "task" else "note") }
    var action by rememberSaveable(capture.id) { mutableStateOf(storedDraft?.action ?: suggestedThoughtAction(capture.read("body"))) }
    var noteTitle by rememberSaveable(capture.id) { mutableStateOf(storedDraft?.noteTitle ?: firstThoughtLine(capture.read("body"))) }
    var noteBody by rememberSaveable(capture.id) { mutableStateOf(storedDraft?.noteBody ?: capture.read("body")) }
    var homeId by rememberSaveable(capture.id) { mutableStateOf(storedDraft?.homeId.orEmpty()) }
    var doDate by rememberSaveable(capture.id) { mutableStateOf(storedDraft?.doDate.orEmpty()) }
    var deadline by rememberSaveable(capture.id) { mutableStateOf(storedDraft?.deadline.orEmpty()) }
    var linksCsv by rememberSaveable(capture.id) { mutableStateOf(storedDraft?.relatedIds.orEmpty().joinToString(",")) }
    var homePicker by rememberSaveable(capture.id) { mutableStateOf(false) }
    var relatedPicker by rememberSaveable(capture.id) { mutableStateOf(false) }
    var more by rememberSaveable(capture.id) { mutableStateOf(false) }
    var preview by rememberSaveable(capture.id) { mutableStateOf(false) }
    var moreMenu by rememberSaveable(capture.id) { mutableStateOf(false) }
    var deletePrompt by rememberSaveable(capture.id) { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember(capture.id) { mutableStateOf("") }
    val activeRecords = records.filter { it.accountId == repository.accountId && !it.deleted && !it.archived }
    val selectedHome = activeRecords.firstOrNull { it.id == homeId }
    val relatedIds = linksCsv.split(',').filter(String::isNotBlank).distinct()
    val selectedRelated = relatedIds.mapNotNull { id -> records.firstOrNull { it.id == id && !it.deleted } }
    val matches = activeRecords.filter { it.kind == "project" && thought.trim().length > 1 && it.read("title").contains(thought.trim(), ignoreCase = true) }
    val captureIndex = captures.indexOfFirst { it.id == capture.id }
    val nextCapture = captures.drop((captureIndex + 1).coerceAtLeast(0)).firstOrNull { it.id != capture.id }
        ?: captures.firstOrNull { it.id != capture.id }

    fun draftValue() = ClarificationDraft(mode, action, noteTitle, noteBody, homeId.takeIf(String::isNotBlank), doDate.takeIf(String::isNotBlank), deadline.takeIf(String::isNotBlank), relatedIds)
    fun queueDraft() {
        val body = thought
        val draft = draftValue()
        scope.launch {
            runCatching { saveMutex.withLock { repository.saveClarificationDraft(capture.id, body, draft) } }
                .onFailure { error = it.message ?: "Your edit could not be saved yet." }
        }
    }
    fun chooseHome(id: String) { homeId = id; homePicker = false; queueDraft() }
    fun toggleRelated(id: String) { linksCsv = (if(id in relatedIds) relatedIds - id else relatedIds + id).distinct().joinToString(","); queueDraft() }
    fun updateThought(value: String) {
        val derivedTitle = noteTitle == firstThoughtLine(thought)
        val derivedBody = noteBody == thought
        thought = value
        if(derivedTitle) noteTitle = firstThoughtLine(value)
        if(derivedBody) noteBody = value
        queueDraft()
    }
    fun next() { saved(nextCapture?.id) }
    fun saveNow() {
        if(saving || thought.isBlank() && !capture.hasAttachments() || mode == "split" && action.isBlank()) return
        saving = true; error = ""
        scope.launch {
            try {
                saveMutex.withLock {
                    repository.clarify(capture.id, thought, mode, action, noteTitle, noteBody, homeId.takeIf(String::isNotBlank), doDate.takeIf(String::isNotBlank), deadline.takeIf(String::isNotBlank), relatedIds)
                }
                next()
            } catch(failure: Exception) { error = failure.message ?: "Could not save this clarification. Your thought is still in Inbox."; saving = false }
        }
    }
    fun archive() {
        if(saving) return
        saving = true
        scope.launch { try { saveMutex.withLock { repository.archive(capture.id, true) }; next() } catch(failure: Exception) { error = failure.message ?: "Could not archive this thought"; saving = false } }
    }
    fun remove() {
        if(saving) return
        saving = true
        scope.launch { try { saveMutex.withLock { repository.delete(capture.id) }; deletePrompt = false; next() } catch(failure: Exception) { error = failure.message ?: "Could not delete this thought"; saving = false } }
    }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(Modifier.fillMaxWidth().background(ClarifySurface, RoundedCornerShape(22.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                val typeLabel = when(capture.read("captureType")) { "task" -> "TASK HINT"; "image" -> "PHOTO"; "link" -> "LINK"; else -> "THOUGHT" }
                Text(typeLabel, color = Color(0xff171412), fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.background(ClarifyOrange, RoundedCornerShape(14.dp)).padding(horizontal = 10.dp, vertical = 6.dp))
                Text("Captured ${formatCaptureTime(capture.createdAt)}", color = ClarifySecondary, fontSize = 11.sp)
            }
            Text("Your thought", fontSize = 12.sp, color = ClarifySecondary, fontWeight = FontWeight.Bold)
            BasicTextField(thought, ::updateThought, enabled = !saving, modifier = Modifier.fillMaxWidth().heightIn(min = 110.dp)
                .background(ClarifyRaised, RoundedCornerShape(16.dp)).padding(14.dp).semantics { contentDescription = "Edit captured thought" },
                textStyle = androidx.compose.ui.text.TextStyle(color = ClarifyText, fontSize = 18.sp, lineHeight = 27.sp))
            val attachmentCount = capture.data["attachmentIds"]?.jsonArray?.size ?: 0
            if(attachmentCount > 0) Copy("$attachmentCount original attachment${if(attachmentCount == 1) "" else "s"} will stay with the Note.")
        }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Heading("What should this become?", 16)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("task", "note", "split").forEach { kind ->
                    val chosen = mode == kind
                    val background = if(!chosen) ClarifyRaised else when(kind) { "task" -> ClarifyBlue; "note" -> ClarifyTeal; else -> ClarifyOrange }
                    TextButton(onClick = { mode = kind; if(kind == "split" && action.isBlank()) action = suggestedThoughtAction(thought); queueDraft() }, enabled = !saving,
                        modifier = Modifier.weight(1f).heightIn(min = 50.dp), contentPadding = PaddingValues(horizontal = 7.dp)) {
                        Row(Modifier.fillMaxWidth().background(background, RoundedCornerShape(15.dp)).padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                            ChoiceMark(kind); Spacer(Modifier.width(6.dp)); Text(kind.replaceFirstChar { it.uppercase() }, color = if(chosen && kind != "task") Color(0xff171412) else ClarifyText, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
            if(mode == "split") {
                OutlinedTextField(action, { action = it; queueDraft() }, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Split Task action" }, label = { Text("Task action") }, placeholder = { Text("What action should be done?") }, shape = RoundedCornerShape(16.dp), minLines = 1)
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Heading("Add to…", 14)
            Copy("Optional. Choose one primary home.")
            OutlinedButton(onClick = { homePicker = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp), shape = RoundedCornerShape(16.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    ChoiceMark(selectedHome?.kind ?: "project")
                    Text(selectedHome?.clarificationTitle() ?: "Add to…", color = ClarifyText, modifier = Modifier.weight(1f))
                    Text("›", color = ClarifySecondary, fontSize = 20.sp)
                }
            }
            matches.take(3).forEach { project ->
                TextButton(onClick = { chooseHome(project.id) }, modifier = Modifier.fillMaxWidth().heightIn(min = 46.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) { ChoiceMark("project"); Text("Suggested Project · ${project.read("title")}", color = ClarifyOrange) }
                }
            }
        }
        if(mode == "task" || mode == "split") {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Heading("Schedule", 14)
                DateChoice("Schedule", doDate) { doDate = it; queueDraft() }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Heading("Connected items", 14)
            Copy("Optional links to related Notes, Tasks, or collections.")
            OutlinedButton(onClick = { relatedPicker = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp), shape = RoundedCornerShape(16.dp)) {
                Text(if(relatedIds.isEmpty()) "⌁  Connect related items" else "⌁  ${relatedIds.size} connected item${if(relatedIds.size == 1) "" else "s"}", color = ClarifyText)
            }
            selectedRelated.forEach { target -> TextButton(onClick = { toggleRelated(target.id) }, modifier = Modifier.fillMaxWidth().heightIn(min = 42.dp)) { Text("${target.clarificationTitle()}  ×", color = ClarifySecondary) } }
        }
        TextButton(onClick = { more = !more }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).background(ClarifyRaised, RoundedCornerShape(16.dp))) {
            Text(if(more) "Hide More options" else "More options", color = ClarifyText)
        }
        if(more) {
            Column(Modifier.fillMaxWidth().background(ClarifyRaised, RoundedCornerShape(18.dp)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if(mode == "note" || mode == "split") {
                    OutlinedTextField(noteTitle, { noteTitle = it; queueDraft() }, label = { Text("Note title") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), singleLine = true)
                    OutlinedTextField(noteBody, { noteBody = it; queueDraft() }, label = { Text("Note content") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), minLines = 3)
                }
                if(mode == "task" || mode == "split") {
                    Heading("Deadline", 13)
                    DateChoice("Deadline", deadline) { deadline = it; queueDraft() }
                }
                TextButton(onClick = { preview = !preview }, modifier = Modifier.heightIn(min = 48.dp)) { Text(if(preview) "Hide preview" else "Preview results", color = ClarifySecondary) }
                if(preview) {
                    Column(Modifier.fillMaxWidth().background(ClarifySurface, RoundedCornerShape(16.dp)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Heading("Preview", 13)
                        if(mode == "note" || mode == "split") { Text(noteTitle.ifBlank { "Untitled Note" }, color = ClarifyText, fontWeight = FontWeight.Bold); Text(noteBody, color = ClarifySecondary, fontSize = 12.sp); Text("Note${selectedHome?.let { " · ${it.kind.replaceFirstChar { char -> char.uppercase() }}: ${it.clarificationTitle()}" } ?: ""}", color = ClarifySecondary, fontSize = 11.sp) }
                        if(mode == "task" || mode == "split") { Text(if(mode == "split") action else thought, color = ClarifyText, fontWeight = FontWeight.Medium); Text("Task · ${if(doDate.isBlank()) "Unscheduled" else "Schedule: ${displayLocalDate(doDate)}"}${if(deadline.isBlank()) "" else " · Deadline: ${displayLocalDate(deadline)}"}", color = ClarifySecondary, fontSize = 11.sp) }
                    }
                }
                Box {
                    OutlinedButton(onClick = { moreMenu = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text("More  ⋯", color = ClarifySecondary) }
                    DropdownMenu(expanded = moreMenu, onDismissRequest = { moreMenu = false }) {
                        DropdownMenuItem(text = { Column { Text("Archive thought"); Text("Restore it from Archive any time", fontSize = 11.sp, color = ClarifySecondary) } }, leadingIcon = { ChoiceMark("archive") }, onClick = { moreMenu = false; archive() })
                        DropdownMenuItem(text = { Column { Text("Delete thought"); Text("Permanently remove after confirmation", fontSize = 11.sp, color = ClarifySecondary) } }, leadingIcon = { Text("×", color = Color(0xffef8e8b), fontSize = 20.sp) }, onClick = { moreMenu = false; deletePrompt = true })
                    }
                }
            }
        }
        if(error.isNotBlank()) Text(error, color = Color(0xffff8c88), fontSize = 12.sp, modifier = Modifier.semantics { contentDescription = "Clarification error" })
        Row(Modifier.fillMaxWidth().background(ClarifySurface, RoundedCornerShape(16.dp)).padding(8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Saved locally as you edit", color = ClarifySecondary, fontSize = 11.sp)
            Button(onClick = ::saveNow, enabled = !saving && (thought.isNotBlank() || capture.hasAttachments()) && (mode != "split" || action.isNotBlank()), modifier = Modifier.heightIn(min = 48.dp), shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.buttonColors(containerColor = ClarifyBlue)) { Text(if(saving) "Saving…" else if(nextCapture != null) "Save & next" else "Save") }
        }
    }

    if(homePicker) {
        var query by rememberSaveable(capture.id) { mutableStateOf("") }
        var newTitle by rememberSaveable(capture.id) { mutableStateOf("") }
        var newKind by rememberSaveable(capture.id) { mutableStateOf("project") }
        var homeError by remember { mutableStateOf("") }
        val choices = activeRecords.filter { it.kind in setOf("project", "area", "resource") && it.clarificationTitle().contains(query, ignoreCase = true) }
        AlertDialog(onDismissRequest = { homePicker = false }, title = { Text("Add to a Project, Area, or Resource", color = ClarifyText) },
            text = { Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(query, { query = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Search collections") }, shape = RoundedCornerShape(14.dp), singleLine = true)
                TextButton(onClick = { homeId = ""; homePicker = false; queueDraft() }, modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)) { Text("No primary home", color = ClarifySecondary) }
                listOf("project" to "Projects", "area" to "Areas", "resource" to "Resources").forEach { (kind, group) ->
                    Heading(group, 13)
                    val groupRecords = choices.filter { it.kind == kind }
                    groupRecords.forEach { option -> TextButton(onClick = { chooseHome(option.id) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) { ChoiceMark(kind); Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) { Text(option.clarificationTitle(), color = ClarifyText); Copy(kind.replaceFirstChar { it.uppercase() }) }; if(option.id == homeId) Text("✓", color = ClarifyOrange) } } }
                    if(groupRecords.isEmpty()) Copy("No ${kind}s yet.")
                }
                HorizontalDivider(color = Color(0xff453d37)); Heading("Create a Project, Area, or Resource", 13)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { listOf("project", "area", "resource").forEach { kind -> FilterChip(selected = newKind == kind, onClick = { newKind = kind }, label = { Text(kind.replaceFirstChar { it.uppercase() }) }) } }
                OutlinedTextField(newTitle, { newTitle = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Name") }, shape = RoundedCornerShape(14.dp), singleLine = true)
                if(homeError.isNotBlank()) Text(homeError, color = Color(0xffff8c88))
                TextButton(enabled = newTitle.isNotBlank(), onClick = { scope.launch { try {
                    val record = when(newKind) {
                        "project" -> repository.create("project", wireJson.encodeToJsonElement(Project(newTitle.trim())) as JsonObject)
                        "area" -> repository.create("area", wireJson.encodeToJsonElement(Area(newTitle.trim())) as JsonObject)
                        else -> repository.create("resource", wireJson.encodeToJsonElement(Resource(newTitle.trim())) as JsonObject)
                    }; chooseHome(record.id)
                } catch(failure: Exception) { homeError = failure.message ?: "Could not create this collection" } } }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Create ${newKind.replaceFirstChar { it.uppercase() }}", color = ClarifyOrange) }
            } }, confirmButton = { TextButton(onClick = { homePicker = false }) { Text("Close") } })
    }
    if(relatedPicker) {
        var pending by rememberSaveable(capture.id) { mutableStateOf(linksCsv) }
        var query by rememberSaveable(capture.id) { mutableStateOf("") }
        val pendingIds = pending.split(',').filter(String::isNotBlank)
        val options = activeRecords.filter { it.id != capture.id && it.kind in setOf("task", "note", "dailyNote", "project", "area", "resource") && it.clarificationTitle().contains(query, ignoreCase = true) }.sortedBy { it.clarificationTitle().lowercase() }
        AlertDialog(onDismissRequest = { relatedPicker = false }, title = { Text("Connect related items", color = ClarifyText) },
            text = { Column(Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedTextField(query, { query = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Search Notes, Tasks, or collections") }, shape = RoundedCornerShape(14.dp), singleLine = true)
                options.forEach { item -> Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(item.id in pendingIds, onCheckedChange = { checked -> pending = (if(checked) pendingIds + item.id else pendingIds - item.id).distinct().joinToString(",") })
                    Column(Modifier.weight(1f)) { Text(item.clarificationTitle(), color = ClarifyText); Copy(if(item.kind == "dailyNote") "Daily note" else item.kind.replaceFirstChar { it.uppercase() }) }
                } }
                if(options.isEmpty()) Copy("No matching active items.")
            } }, confirmButton = { TextButton(onClick = { linksCsv = pending; relatedPicker = false; queueDraft() }) { Text("Save connections") } }, dismissButton = { TextButton(onClick = { relatedPicker = false }) { Text("Cancel") } })
    }
    if(deletePrompt) AlertDialog(onDismissRequest = { deletePrompt = false }, title = { Text("Delete this thought?", color = ClarifyText) }, text = { Text("This permanently removes the Inbox thought and its linked capture files.", color = ClarifySecondary) }, confirmButton = { TextButton(onClick = ::remove) { Text("Delete thought", color = Color(0xffff8c88)) } }, dismissButton = { TextButton(onClick = { deletePrompt = false }) { Text("Cancel") } })
}

@Composable internal fun ProcessedCaptureContent(capture: Record, records: List<Record>, open: (Record) -> Unit) {
    val processedIds = (capture.data["processedIds"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty() +
        records.filter { it.accountId == capture.accountId && !it.deleted && it.kind in setOf("task", "note") && it.read("sourceCaptureId") == capture.id }.map { it.id }).distinct()
    Column(Modifier.fillMaxWidth().background(ClarifySurface, RoundedCornerShape(22.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("PROCESSED THOUGHT", color = Color(0xff171412), fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.background(ClarifyTeal, RoundedCornerShape(14.dp)).padding(horizontal = 10.dp, vertical = 6.dp))
        Copy("Captured ${formatCaptureTime(capture.createdAt)} · Processed ${capture.data["processedAt"]?.jsonPrimitive?.longOrNull?.let { java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault()).format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(java.util.Locale.getDefault())) }.orEmpty()}")
        Heading("Original wording", 17); Text(capture.data["originalBody"]?.jsonPrimitive?.contentOrNull ?: capture.read("body"), color = ClarifyText, fontSize = 15.sp, lineHeight = 22.sp)
        val original = capture.data["originalBody"]?.jsonPrimitive?.contentOrNull ?: capture.read("body")
        if(capture.read("body") != original) { Heading("Edited thought", 16); Text(capture.read("body"), color = ClarifySecondary, fontSize = 14.sp, lineHeight = 21.sp) }
        Heading("Resulting items", 16)
        processedIds.forEach { id -> val result = records.firstOrNull { it.id == id && !it.deleted }; if(result != null) OutlinedButton(onClick = { open(result) }, modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp)) { Text("${result.kind.replaceFirstChar { it.uppercase() }} · ${result.clarificationTitle()}   ›", color = ClarifyText) } else Copy("This result is no longer available.") }
        if(processedIds.isEmpty()) Copy("This history entry has no available results.")
    }
}

@Composable internal fun ArchivedThoughtContent(capture: Record, restore: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(ClarifySurface, RoundedCornerShape(22.dp)).padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("ARCHIVED THOUGHT", color = ClarifySecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Copy("Captured ${formatCaptureTime(capture.createdAt)}")
        Text(capture.read("body").ifBlank { "Photo capture" }, color = ClarifyText, fontSize = 18.sp, lineHeight = 26.sp)
        Action("Restore to Inbox", true, restore)
    }
}

private fun displayLocalDate(value: String) = runCatching { LocalDate.parse(value).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)) }.getOrDefault(value)
