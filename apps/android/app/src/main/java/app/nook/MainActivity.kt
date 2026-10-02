package app.nook

import android.os.Bundle
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.room.Room
import app.nook.data.*
import app.nook.updates.AboutUpdatesCard
import app.nook.updates.UpdateBanner
import app.nook.updates.UpdateManager
import app.nook.updates.UpdateUiState
import app.nook.updates.scheduleUpdateChecks
import app.nook.data.Record
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

private val Background = Color(0xff171412)
private val Surface = Color(0xff221d1a)
private val Elevated = Color(0xff342d28)
private val Border = Color(0xff453d37)
private val Text = Color(0xfff5eee7)
private val Secondary = Color(0xffcbbfb4)
private val Blue = Color(0xff3270e6)
private val Orange = Color(0xffff7e1d)
private val Yellow = Color(0xffffce00)
private val Teal = Color(0xff3fae9b)
private val Inter = FontFamily(Font(R.font.inter))
private fun Record.value(key: String) = data[key]?.jsonPrimitive?.contentOrNull.orEmpty()
private fun Record.title() = if(kind == "dailyNote") "Daily note · ${value("date")}" else value("title").ifBlank { value("body").take(100).ifBlank { kind } }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        val preferences = getSharedPreferences("nook-local", MODE_PRIVATE)
        val updates = UpdateManager.from(applicationContext)
        scheduleUpdateChecks(applicationContext)
        lifecycleScope.launch(Dispatchers.IO) { AppGraph.initializeFirebase(applicationContext) }
        setContent {
            NookTheme {
                val firebase by AppGraph.firebase.collectAsStateWithLifecycle()
                val accountId by AppGraph.accounts(applicationContext).accountId.collectAsStateWithLifecycle()
                val updateState by updates.state.collectAsStateWithLifecycle()
                val sessionState = firebase.auth?.accounts?.state?.collectAsStateWithLifecycle()?.value
                val localRepository = remember(accountId) { AppGraph.repository(applicationContext) }
                val repository = sessionState?.repository ?: localRepository
                var onboarded by rememberSaveable { mutableStateOf(preferences.getBoolean("onboarded", false)) }
                if (!firebase.ready) Copy("Opening your Nook…", Modifier.padding(24.dp))
                else if (sessionState?.changing == true) Copy("Switching accounts…", Modifier.padding(24.dp))
                else Column(Modifier.fillMaxSize()) {
                    firebase.error?.let { Copy(it, Modifier.padding(16.dp)) }
                    if (!onboarded) Welcome { preferences.edit().putBoolean("onboarded", true).apply(); onboarded = true }
                    else key(repository.accountId) { Nook(repository, intent.getStringExtra("page") ?: "Today", intent.getStringExtra("recordId"), updates, updateState) }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch(Dispatchers.IO) { UpdateManager.from(applicationContext).checkAutomatically() }
    }
}

@Composable fun NookTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(primary = NookReadableBlue, onPrimary = Background, background = Background, onBackground = Text,
        surface = Surface, onSurface = Text, surfaceVariant = Elevated, onSurfaceVariant = Secondary, outline = Border, inverseSurface = Elevated, inverseOnSurface = Text),
        typography = Typography().let { typography -> typography.copy(
            displayLarge = typography.displayLarge.copy(fontFamily = Inter), displayMedium = typography.displayMedium.copy(fontFamily = Inter), displaySmall = typography.displaySmall.copy(fontFamily = Inter),
            headlineLarge = typography.headlineLarge.copy(fontFamily = Inter), headlineMedium = typography.headlineMedium.copy(fontFamily = Inter), headlineSmall = typography.headlineSmall.copy(fontFamily = Inter),
            bodyLarge = typography.bodyLarge.copy(fontFamily = Inter), bodyMedium = typography.bodyMedium.copy(fontFamily = Inter),
            bodySmall = typography.bodySmall.copy(fontFamily = Inter), titleMedium = typography.titleMedium.copy(fontFamily = Inter), titleSmall = typography.titleSmall.copy(fontFamily = Inter),
            titleLarge = typography.titleLarge.copy(fontFamily = Inter), labelLarge = typography.labelLarge.copy(fontFamily = Inter), labelMedium = typography.labelMedium.copy(fontFamily = Inter), labelSmall = typography.labelSmall.copy(fontFamily = Inter)) }, content = content)
}

@Composable private fun Heading(text: String, size: Int = 24) { Text(text, fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = size.sp, lineHeight = (size * 1.2).sp, color = Text) }
@Composable private fun Copy(text: String, modifier: Modifier = Modifier) { Text(text, modifier, fontFamily = Inter, fontSize = 14.sp, color = Secondary) }
@Composable internal fun Dot() {
    Image(painterResource(R.drawable.nook_mascot_icon), contentDescription = null, modifier = Modifier.size(28.dp))
}
@Composable private fun Panel(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().background(Surface, RoundedCornerShape(20.dp)).border(1.dp, Border, RoundedCornerShape(20.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
}
@Composable private fun Action(text: String, primary: Boolean = false, enabled: Boolean = true, modifier: Modifier = Modifier, click: () -> Unit) {
    NookButton(onClick = click, enabled = enabled, modifier = modifier, shape = CircleShape, colors = ButtonDefaults.buttonColors(containerColor = if (primary) Blue else Elevated, contentColor = if(primary) Color.White else Text), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp)) { Text(text, fontFamily = Inter) }
}
@Composable private fun Welcome(start: () -> Unit) {
    var learning by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(Background).safeDrawingPadding().padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) { Dot(); Heading("nook", 18) }
        Image(painter = painterResource(R.drawable.nook_mascot), contentDescription = "Nook's dormouse mascot",
            modifier = Modifier.align(Alignment.CenterHorizontally).size(84.dp))
        Heading("Everything on your mind has somewhere to belong.", 30)
        Copy("Capture first. Nook helps you clarify, connect and act later—without slowing you down.")
        Panel { Heading("Capture in seconds", 18); Copy("Text · tasks · links · screenshots") }
        Panel { Heading("Organize when ready", 18); Copy("Projects · Areas · Resources") }
        Action("Use Nook without an account", primary = true, click = start)
        Copy("Your data stays usable offline. You can enable sync later.")
        Action("How Nook organizes") { learning = true }
    }
    if(learning) ParaGuide { learning = false }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable private fun Nook(repository: NookRepository, initialPage: String = "Today", initialRecord: String? = null, updates: UpdateManager, updateState: UpdateUiState) {
    val records by repository.observeAll().collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var page by rememberSaveable { mutableStateOf(initialPage) }
    var selectedId by rememberSaveable { mutableStateOf(initialRecord) }
    var capturing by rememberSaveable { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    val snackbar = remember { SnackbarHostState() }
    fun act(success: String = "Saved locally", block: suspend () -> Unit) { scope.launch { try { block(); app.nook.integration.scheduleSystemRefresh(context); message = success } catch (error: Exception) { message = error.message ?: "Could not save. Please try again." } } }
    LaunchedEffect(message) { if (message.isNotEmpty()) { snackbar.showSnackbar(message); message = "" } }
    fun open(record: Record) { selectedId = record.id; page = when(record.kind) { "capture" -> "Inbox"; "project", "task" -> "Projects"; "area" -> "Areas"; else -> "Resources" } }
    val selected = records.find { it.id == selectedId }
    BackHandler(selectedId != null) { selectedId = null }
    val active = records.filter { !it.archived }
    val captures = active.filter { it.kind == "capture" }
    val today = LocalDate.now().toString()
    val tasks = active.filter { it.kind == "task" && it.value("completed") != "true" }
    Scaffold(containerColor = Background, snackbarHost = { SnackbarHost(snackbar) }, bottomBar = {
        if (page != "Sync" || !repository.accountId.startsWith("local:")) {
        NookNavigation(page) { destination -> page = destination; selectedId = null }
        }
    }, floatingActionButton = { if (selected == null && page != "Sync") FloatingActionButton(onClick = { capturing = true }, containerColor = Blue, shape = CircleShape, modifier = Modifier.semantics { contentDescription = "Quick capture" }) { Text("+", fontSize = 28.sp, color = Color.White) } }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if(selected?.kind != "note") Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) { Dot(); Heading("nook", 18) }
                Text(if(selected?.kind == "capture" && selected.archived) "Archived" else if(selected?.kind == "capture") "${captures.indexOfFirst { it.id == selected.id } + 1} of ${captures.size}" else if(selected?.kind == "project") "${selected.value("progress").toDoubleOrNull()?.toInt() ?: 0}%" else if(page == "Inbox" && selected == null) "${captures.size} ${if(captures.size == 1) "item" else "items"}" else LocalDate.now().format(DateTimeFormatter.ofPattern("MMM d")), fontFamily = Inter, fontSize = 12.sp, lineHeight = 18.sp, color = Secondary, modifier = Modifier.background(Elevated, RoundedCornerShape(14.dp)).padding(horizontal = 12.dp, vertical = 5.dp))
            }
            UpdateBanner(
                state = updateState,
                onLater = updates::later,
                onDownload = { scope.launch { updates.download() } },
                onCancel = updates::cancelDownload,
                onInstall = { scope.launch { updates.install() } },
                onRetryCheck = { scope.launch { updates.checkManually() } }
            )
            if (page != "Sync" && selected?.kind != "note") Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        Text(if(selected?.kind == "capture") "Clarify" else selected?.title() ?: if(page == "Today") "Good morning." else page, fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 30.sp, lineHeight = 42.sp, color = Text)
                        if(selected?.kind == "capture") Copy("What is this useful for?")
                        if(selected == null && page == "Today") {
                            val dueCount = todayActions(tasks, active.filter { it.kind == "project" }, today).size
                            Copy(if(dueCount == 0) "A little room to think." else "$dueCount ${if(dueCount == 1) "thing" else "things"} worth doing.")
                        }
                    }
                    if(selected == null && page == "Today") Image(
                        painter = painterResource(R.drawable.nook_mascot_icon),
                        contentDescription = "Nook's dormouse mascot",
                        modifier = Modifier.size(72.dp)
                    )
                }
            }
            if (selected?.kind == "capture") {
                Column(Modifier.fillMaxWidth().heightIn(min = 168.dp).background(Surface, RoundedCornerShape(22.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    val captureLabel = when(selected.value("captureType")) { "task" -> "TASK"; "image" -> "PHOTO"; "link" -> "LINK"; else -> "THOUGHT" }
                    Text(captureLabel, fontFamily = Inter, fontSize = 12.sp, lineHeight = 18.sp, color = Background,
                        modifier = Modifier.background(Orange, RoundedCornerShape(14.dp)).padding(horizontal = 12.dp, vertical = 5.dp))
                    Text(selected.value("body").ifBlank { "Photo capture" }, fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 20.sp, lineHeight = 28.sp, color = Text)
                    val minutes = ((System.currentTimeMillis() - selected.createdAt).coerceAtLeast(0) / 60_000)
                    Text(if(minutes == 0L) "Captured just now" else "Captured $minutes ${if(minutes == 1L) "minute" else "minutes"} ago", fontFamily = Inter, fontSize = 11.sp, lineHeight = 18.sp, color = Color(0xff9c9187))
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    var destinationProject by rememberSaveable(selected.id) { mutableStateOf("") }
                    Text("Turn it into…", fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 13.sp, lineHeight = 18.2.sp, color = Text)
                    RecordContextPicker("Assign to project", destinationProject, records.filter { it.kind == "project" && !it.deleted && !it.archived && it.accountId == repository.accountId }) { destinationProject = it }
                    ClarifyDestinations(archived = selected.archived) { kind ->
                        if(kind == "archive") act(if(selected.archived) "Restored" else "Archived") { repository.archive(selected.id, !selected.archived); selectedId = null }
                        else act("Organized locally") { open(repository.process(selected.id, kind, destinationProject.takeIf { it.isNotBlank() && kind in listOf("task", "note", "resource") })) }
                    }
                    Action("Delete") { act("Deleted locally") { repository.delete(selected.id); selectedId = null } }
                }
                AttachmentPanel(selected.id, records, repository)
                key(selected.id) { AiAssist(repository, selected, records) }
            } else if (selected != null) key(selected.id) { RecordScreen(selected, records, repository, ::open, { success, work -> act(success, work) }) }
            else when(page) {
                "Today" -> {
                    TodayCards(tasks, today, captures.size, ::open, { task ->
                        act { repository.update(task.id) { it.copy(data = JsonObject(it.data + ("completed" to JsonPrimitive(true)))) } }
                    }, { page = "Inbox" }, projects = active.filter { it.kind == "project" })
                }
                "Inbox" -> InboxContent(repository, captures, ::open) { act("Saved to Inbox") {} }
                "More", "Library" -> listOf("Resources", "Areas", "Calendar", "Search", "Archive", "Weekly review", "Reminders", "Settings").forEach { destination -> Action(destination) { page = destination } }
                "Search" -> SearchScreen(repository, ::open)
                "Calendar" -> CalendarScreen(active, repository, ::open, { success, work -> act(success, work) })
                "Settings" -> DataSettings(repository, { page = "Sync" }, { success, work -> act(success, work) }, updates, updateState)
                "Sync" -> { val firebase by AppGraph.firebase.collectAsStateWithLifecycle(); app.nook.integration.AccountControls(repository, firebase) { page = "Today" } }
                "Reminders" -> app.nook.integration.RemindersScreen(repository, records) { success, work -> act(success, work) }
                "Weekly review" -> app.nook.integration.WeeklyReviewScreen(repository, records, ::open, { page = "Inbox" }, { success, work -> act(success, work) })
                else -> {
                    val kind = when(page) { "Projects" -> "project"; "Areas" -> "area"; else -> "resource" }
                    var name by rememberSaveable(page) { mutableStateOf("") }
                    if(page != "Archive") { Field("New $kind", name) { name = it }; Action("Add $kind", enabled = name.isNotBlank()) { act { val data = when(kind) { "project" -> wireJson.encodeToJsonElement(Project(name)); "area" -> wireJson.encodeToJsonElement(Area(name)); else -> wireJson.encodeToJsonElement(Resource(name)) }; open(repository.create(kind, data as JsonObject)); name = "" } } }
                    records.filter { if(page == "Archive") it.archived else !it.archived && (it.kind == kind || page == "Resources" && it.kind == "note") }.forEach { record -> Panel { Copy(record.kind.uppercase()); TextButton(onClick = { open(record) }) { Text(record.title(), color = Text) }; if(record.kind == "project") LinearProgressIndicator(progress = { (record.value("progress").toFloatOrNull() ?: 0f) / 100f }, modifier = Modifier.fillMaxWidth(), color = Orange, trackColor = Border) } }
                }
            }
            when {
                selected?.kind == "capture" || page == "Inbox" -> ParaTip(repository, records, "inbox")
                selected?.kind == "area" || page == "Areas" -> ParaTip(repository, records, "area")
                selected?.kind == "project" || page == "Projects" -> ParaTip(repository, records, "project")
                page == "Archive" -> ParaTip(repository, records, "archive")
                selected?.kind == "resource" || page == "Resources" -> ParaTip(repository, records, "resource")
            }
            Spacer(Modifier.height(80.dp))
        }
    }
    if(capturing) {
        var body by rememberSaveable { mutableStateOf("") }; var type by rememberSaveable { mutableStateOf("text") }; var saving by remember { mutableStateOf(false) }
        var photos by remember { mutableStateOf<List<Uri>>(emptyList()) }
        val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris -> photos = uris.take(20); if(photos.isNotEmpty()) type = "image" }
        val focus = remember { FocusRequester() }
        val captureSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(sheetState = captureSheetState, onDismissRequest = { if(!saving) capturing = false }, containerColor = Elevated, shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)) {
            Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Quick capture", modifier = Modifier.weight(1f), fontFamily = Inter, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Text)
                    IconButton(onClick = { capturing = false }, enabled = !saving, modifier = Modifier.semantics { contentDescription = "Close quick capture" }) { Text("×", fontSize = 24.sp, color = Secondary) }
                }
                OutlinedTextField(body, { body = it }, modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp).focusRequester(focus), maxLines = 4, placeholder = { Text("What’s on your mind?") }, label = { Text("Thought") }, shape = RoundedCornerShape(16.dp))
                LaunchedEffect(Unit) { focus.requestFocus() }
                Copy("Saved to Inbox by default")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) { FilterChip(selected = type == "task", onClick = { type = if(type == "task") "text" else "task" }, label = { Text("Task") }); FilterChip(selected = type == "link", onClick = { type = if(type == "link") "text" else "link" }, label = { Text("Link") }); TextButton(onClick = { photoPicker.launch("image/*") }) { Text("Photo") } }
                if(photos.isNotEmpty()) Copy("${photos.size} photos attached")
                Action(if(saving) "Saving…" else "Save", true, (body.isNotBlank() || photos.isNotEmpty()) && !saving, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { saving = true; scope.launch { try {
                    val originals = withContext(Dispatchers.IO) { var totalBytes = 0L; photos.map { uri ->
                        val filename = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor -> if(cursor.moveToFirst()) cursor.getString(0) else "photo" } ?: "photo"
                        val bytes = context.contentResolver.openInputStream(uri)?.use { input -> readLimited(input, 50 * 1024 * 1024) } ?: error("Photo unavailable")
                        totalBytes += bytes.size; require(totalBytes <= 50 * 1024 * 1024) { "Capture attachments exceed 50 MB in total" }
                        OriginalInput(filename, context.contentResolver.getType(uri) ?: "application/octet-stream", bytes)
                    } }
                    repository.capture(body, type, originals); app.nook.integration.scheduleSystemRefresh(context); capturing = false; message = "Saved to Inbox"
                } catch(error: Exception) { message = error.message ?: "Could not save"; saving = false } } }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

private fun readLimited(input: java.io.InputStream, max: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
    while(true) { val count = input.read(buffer); if(count < 0) break; require(output.size().toLong() + count <= max) { "File exceeds import size limit" }; output.write(buffer, 0, count) }
    return output.toByteArray()
}

@Composable private fun DataSettings(repository: NookRepository, openSync: () -> Unit, act: (String, suspend () -> Unit) -> Unit, updates: UpdateManager, updateState: UpdateUiState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri -> if(uri != null) act("Backup exported") { withContext(Dispatchers.IO) {
        val bytes = repository.exportBackup(); context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("Could not open backup destination")
    } } }
    val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if(uri != null) act("Backup restored") { withContext(Dispatchers.IO) {
        val bytes = context.contentResolver.openInputStream(uri)?.use { readLimited(it, 250 * 1024 * 1024) } ?: error("Backup unavailable"); repository.restoreBackup(bytes)
    } } }
    AboutUpdatesCard(
        state = updateState,
        onCheck = { scope.launch { updates.checkManually() } },
        onDownload = { scope.launch { updates.download() } },
        onCancel = updates::cancelDownload,
        onInstall = { scope.launch { updates.install() } }
    )
    Panel { Heading("On this device", 18); Copy(if (repository.accountId.startsWith("local:")) "Local-only · saved locally" else "Sync enabled · saved locally"); Action("Sync", click = openSync); Copy("AI is optional. Capture and organization work offline.") }
    Panel { Heading("Data & Export", 18); Copy("Your notes, structured data and original attachments belong to you."); Action("Export Nook backup") { export.launch("nook-${LocalDate.now()}.zip") }; Action("Restore Nook backup") { restore.launch(arrayOf("application/zip", "application/octet-stream")) } }
    Panel { AiSettings(repository) }
    Panel { ParaPreferences(repository) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun ClarifyDestinations(archived: Boolean = false, choose: (String) -> Unit) {
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("task", "note", "project", "resource", "archive").forEach { kind ->
            val color = when(kind) { "task" -> Blue; "note" -> Teal; "project" -> Orange; "resource" -> Color(0xff5c4db8); else -> Elevated }
            TextButton(onClick = { choose(kind) }, modifier = Modifier.width(96.dp).heightIn(min = 48.dp), contentPadding = PaddingValues(0.dp)) {
                Text(if(kind == "archive" && archived) "Restore" else kind.replaceFirstChar { it.uppercase() }, fontFamily = Inter, fontSize = 12.sp, lineHeight = 18.sp,
                    color = if(kind in listOf("note", "project")) Background else if(kind == "task") Color.White else Text,
                    modifier = Modifier.fillMaxWidth().background(color, RoundedCornerShape(14.dp)).padding(horizontal = 12.dp, vertical = 5.dp))
            }
        }
    }
}

@Composable private fun Field(label: String, value: String, multiline: Boolean = false, change: (String) -> Unit) { OutlinedTextField(value, change, label = { Text(label) }, modifier = Modifier.fillMaxWidth().then(if(multiline) Modifier.heightIn(min = 140.dp) else Modifier), singleLine = !multiline, shape = RoundedCornerShape(16.dp)) }
@Composable private fun TaskRow(record: Record, open: () -> Unit, complete: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(record.value("completed") == "true", onCheckedChange = { complete() }, modifier = Modifier.semantics { contentDescription = if(record.value("completed") == "true") "Mark ${record.title()} incomplete" else "Complete ${record.title()}" }); TextButton(onClick = open) { Column { Text(record.title(), color = Text); Copy(record.value("doDate").ifBlank { "Next action" }, Modifier.padding(top = 4.dp)) } } }
}
@Composable internal fun RecordScreen(record: Record, records: List<Record>, repository: NookRepository, open: (Record) -> Unit, act: (String, suspend () -> Unit) -> Unit) {
    val projectFor=remember(records,record.accountId) { projectResolver(records,record.accountId) }
    val areaFor=remember(records,record.accountId) { areaResolver(records,record.accountId) }
    var draft by remember(record.id) { mutableStateOf(record.data) }
    var baseline by remember(record.id) { mutableStateOf(record.data) }
    LaunchedEffect(record.data) { if(draft == baseline) draft = record.data; baseline = record.data }
    fun value(key: String) = draft[key]?.jsonPrimitive?.contentOrNull.orEmpty()
    fun field(key: String, value: String) { draft = JsonObject(draft + (key to JsonPrimitive(value))) }
    fun optional(key: String, value: String) { draft = JsonObject(if(value.isBlank()) draft - key else draft + (key to JsonPrimitive(value))) }
    suspend fun persistDraft() { repository.update(record.id) { current ->
        val retained = if(current.kind == "task") setOf("reminderId", "recurrenceId") else setOf("attachmentIds")
        current.copy(data = JsonObject(draft.filterKeys { it !in retained } + current.data.filterKeys { it in retained }))
    } }
    fun openSaved(target: Record) {
        if(draft == baseline) open(target)
        else act("") { persistDraft(); open(target) }
    }
    if(record.kind == "note") {
        val capture = rememberAttachmentCapture(record.id, repository)
        NoteRecordContent(record, draft, records, repository, ::field, ::optional, ::persistDraft, ::openSaved, act,
            onAttach = capture.launch, attachmentStatus = if(capture.adding) "Adding attachment…" else capture.error, attachmentBusy = capture.adding)
        AttachmentPanel(record.id, records, repository, capture)
        AiAssist(repository, record, records)
        return
    }
    if(record.kind == "project") ProjectOverview(record.copy(data = draft), records, ::openSaved)
    val editor: @Composable () -> Unit = { Panel {
        if(record.kind == "dailyNote") {
            Heading(LocalDate.parse(value("date")).dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.getDefault()), 24)
            Heading("What happened today?", 14)
            Copy("Write freely. This note is optional and automatically linked to its date.")
        }
        if(record.kind != "dailyNote") Field("Title", value("title")) { field("title", it) }
        when(record.kind) {
            "task" -> { DateChoice("Do date", value("doDate")) { optional("doDate", it) }; Copy("When you intend to work on it"); DateChoice("Deadline", value("deadline")) { optional("deadline", it) }; Copy("When it must be finished"); Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(value("completed") == "true", modifier = Modifier.semantics { contentDescription = "Completed" }, onCheckedChange = { draft = JsonObject(draft + ("completed" to JsonPrimitive(it))) }); Text("Completed") } }
            "project" -> { Field("Outcome", value("outcome"), true) { field("outcome", it) }; DateChoice("Target date", value("targetDate")) { optional("targetDate", it) }; Copy("Progress · ${value("progress")}%"); Slider(value("progress").toFloatOrNull() ?: 0f, modifier = Modifier.semantics { contentDescription = "Project progress" }, onValueChange = { draft = JsonObject(draft + ("progress" to JsonPrimitive(it.toDouble()))) }, valueRange = 0f..100f) }
            "area" -> { Field("Ongoing responsibility", value("responsibility"), true) { field("responsibility", it) }; Field("Standards", value("standards"), true) { field("standards", it) } }
            "resource" -> { Field("Description", value("description"), true) { field("description", it) }; Field("Link", value("url")) { optional("url", it) } }
            "dailyNote" -> app.nook.integration.NoteEditor(value("body"), { field("body", it) }, records.filter { it.kind == "note" && it.id != record.id && !it.deleted }, ::openSaved)
        }
        if(record.kind in listOf("task", "note", "resource")) RecordContextPicker("Project", value("projectId"), records.filter { it.kind == "project" && !it.archived && !it.deleted }) { optional("projectId", it) }
        if(record.kind in listOf("task", "project", "note", "resource")) RecordContextPicker("Area", value("areaId"), records.filter { it.kind == "area" && !it.archived && !it.deleted }) { optional("areaId", it) }
        if(record.kind == "note") RecordContextPicker("Resource", value("resourceId"), records.filter { it.kind == "resource" && !it.archived && !it.deleted }) { optional("resourceId", it) }
        if(record.kind == "project") RecordContextPicker("Next action", value("nextActionId"), records.filter { it.kind == "task" && projectFor(it) == record.id && it.value("completed") != "true" && !it.archived && !it.deleted }) { optional("nextActionId", it) }
        Action("Save", true) { act("Saved locally") { persistDraft() } }
        Row { Action(if(record.archived) "Restore" else "Archive") { act(if(record.archived) "Restored" else "Archived") { repository.archive(record.id, !record.archived) } }; Action("Delete") { act("Deleted locally") { repository.delete(record.id) } } }
    }
    }
    if(record.kind != "project") editor()
    if(record.kind == "task") app.nook.integration.TaskReminderEditor(record, records, repository, act)
    if(record.kind == "task") app.nook.integration.TaskRecurrenceEditor(record, records, repository, act)
    if(record.kind == "dailyNote") app.nook.integration.DailyRelatedEditor(draft["relatedIds"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty(), records, record.id,
        { ids -> draft = JsonObject(draft + ("relatedIds" to JsonArray(ids.map(::JsonPrimitive)))) }, ::openSaved)
    if(record.kind in listOf("project", "task")) Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Heading(if(record.kind == "task") "Subtasks" else "Tasks", if(record.kind == "project") 15 else 18)
        records.filter { it.kind == "task" && !it.archived && !it.deleted && it.accountId == record.accountId && (if(record.kind == "project") projectFor(it) else it.value("parentTaskId")) == record.id }
            .sortedWith(compareBy<Record> { it.createdAt }.thenBy { it.id })
            .forEach { task -> ProjectTaskRow(task, { openSaved(task) }) { act("Saved locally") { repository.update(task.id) { it.copy(data = JsonObject(it.data + ("completed" to JsonPrimitive(it.value("completed") != "true")))) } } } }
        var name by rememberSaveable(record.id) { mutableStateOf("") }
        var adding by rememberSaveable(record.id) { mutableStateOf(false) }
        if(record.kind == "project") TextButton(onClick = { adding = !adding }, modifier = Modifier.heightIn(min = 48.dp).semantics { stateDescription = if(adding) "Expanded" else "Collapsed" }) { Text(if(adding) "Hide new task" else "+ New task", color = NookReadableBlue) }
        if(record.kind == "task" || adding) {
            Field("Next action", name) { name = it }
            Action("Add task", enabled = name.isNotBlank()) { act("Task added") { persistDraft(); repository.create("task", wireJson.encodeToJsonElement(Task(name, projectId = if(record.kind == "project") record.id else value("projectId").ifBlank { null }, areaId = value("areaId").ifBlank { null }, parentTaskId = if(record.kind == "task") record.id else null)) as JsonObject); name = ""; adding = false } }
        }
    }
    if(record.kind == "project") {
        var editing by rememberSaveable(record.id) { mutableStateOf(false) }
        val focus = LocalFocusManager.current
        TextButton(onClick = { editing = !editing; if(!editing) focus.clearFocus() }, modifier = Modifier.heightIn(min = 48.dp).semantics { stateDescription = if(editing) "Expanded" else "Collapsed" }) {
            Text(if(editing) "Hide project details" else "Edit project details", color = Secondary)
        }
        if(editing) editor()
    }
    if(record.kind == "note") Panel { Heading("Backlinks", 18); records.filter { it.kind in listOf("note", "dailyNote") && it.id != record.id && app.nook.integration.outgoingNotes(it.value("body"), records.filter { note -> note.kind == "note" }).contains(record.id) }.forEach { note -> TextButton(onClick = { openSaved(note) }) { Text(note.title()) } } }
    if(record.kind == "area") {
        Heading("Active projects", 15); records.filter { it.kind == "project" && !it.archived && it.value("areaId") == record.id }.forEachIndexed { index, project -> AreaProjectRow(project.title(), index) { openSaved(project) } }
        Panel { Heading("Responsibilities", 15); records.filter { it.kind == "task" && !it.archived && areaFor(it) == record.id }.forEach { task -> TaskRow(task, { openSaved(task) }) { act("Saved locally") { repository.update(task.id) { it.copy(data = JsonObject(it.data + ("completed" to JsonPrimitive(it.value("completed") != "true")))) } } } } }
    }
    if(record.kind in listOf("project", "area", "resource")) Panel {
        Heading(if(record.kind == "resource") "Notes" else "Notes & resources", 15)
        val contextKey = when(record.kind) { "project" -> "projectId"; "area" -> "areaId"; else -> "resourceId" }
        records.filter { (it.kind == "note" || record.kind != "resource" && it.kind == "resource") && !it.archived && (if(contextKey == "areaId") areaFor(it) == record.id else it.value(contextKey) == record.id) }.forEach { note -> Action(note.title()) { openSaved(note) } }
        Action("Add note") { act("Note created") { persistDraft(); open(repository.create("note", JsonObject((wireJson.encodeToJsonElement(Note(body = "")) as JsonObject) + (contextKey to JsonPrimitive(record.id))))) } }
    }
    AttachmentPanel(record.id, records, repository)
    AiAssist(repository, record, records)
}

private data class AttachmentCapture(val launch: () -> Unit, val adding: Boolean, val error: String)
@Composable private fun rememberAttachmentCapture(ownerId: String, repository: NookRepository): AttachmentCapture {
    val context = LocalContext.current; val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf("") }
    var adding by remember { mutableStateOf(false) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if(uris.isNotEmpty()) scope.launch { adding = true; error = ""; try {
            val originals = app.nook.integration.readOriginals(context, uris)
            check(AppGraph.repository(context).accountId == repository.accountId) { "The active account changed. Reopen this note." }
            repository.attach(ownerId, originals)
            app.nook.integration.scheduleSystemRefresh(context)
        } catch(exception: Exception) { error = exception.message ?: "Could not add attachment" } finally { adding = false } }
    }
    return AttachmentCapture({ if(!adding) pick.launch("*/*") }, adding, error)
}
@Composable private fun AttachmentPanel(ownerId: String, records: List<Record>, repository: NookRepository, captureOverride: AttachmentCapture? = null) {
    val attachments = records.filter { it.kind == "attachment" && !it.deleted && it.accountId == repository.accountId && it.value("ownerId") == ownerId }
    val context = LocalContext.current; val scope = rememberCoroutineScope()
    var chosen by remember { mutableStateOf<Record?>(null) }; var error by remember { mutableStateOf("") }
    val capture = captureOverride ?: rememberAttachmentCapture(ownerId, repository)
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val attachment = chosen
        if(uri != null && attachment != null) scope.launch { try { withContext(Dispatchers.IO) {
            val original = repository.db.records().original(repository.accountId, attachment.id) ?: error("Original file unavailable")
            context.contentResolver.openOutputStream(uri)?.use { it.write(original.bytes) } ?: error("Could not open file destination")
        } } catch(exception: Exception) { error = exception.message ?: "Could not save original" } }
    }
    Panel { Heading("Attachments", 18); Action(if(capture.adding) "Adding…" else "Add attachment", enabled = !capture.adding, click = capture.launch); attachments.forEach { attachment -> Action("Save ${attachment.value("filename")}") { chosen = attachment; save.launch(attachment.value("filename")) } }; if(error.isNotEmpty() || capture.error.isNotEmpty()) Copy(error.ifEmpty { capture.error }) }
}
@Composable internal fun SearchScreen(repository: NookRepository, open: (Record) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val results by remember(repository, query) { repository.observeSearch(query) }.collectAsStateWithLifecycle(initialValue = emptyList())
    Field("Search your Nook", query) { query = it }; Copy("Local search · project:Title · area:Title · before:2026-10-01")
    results.forEach { record -> Panel { Copy(record.kind.uppercase()); TextButton(onClick = { open(record) }) { Text(record.title(), color = Text) } } }
}
@Composable internal fun CalendarScreen(records: List<Record>, repository: NookRepository, open: (Record) -> Unit, act: (String, suspend () -> Unit) -> Unit) {
    val live = remember(records, repository.accountId) { records.filter { it.accountId == repository.accountId && !it.deleted && !it.archived } }
    var date by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    val selectedDay = runCatching { LocalDate.parse(date) }.getOrNull()?.takeIf { Regex("\\d{4}-\\d{2}-\\d{2}").matches(date) }
    val reminders = remember(records, repository.accountId) {
        val targets = live.map { it.accountId to it.id }.toSet()
        live.filter { it.kind == "reminder" &&
            (it.value("type") == "inbox" || (it.accountId to it.value("targetId")) in targets) }
            .sortedWith(compareBy<Record> { it.value("scheduledAt").toLong() }.thenBy { it.id })
    }
    val scheduledDays = remember(live, reminders) { live.filter { it.kind != "reminder" }.plus(reminders).flatMap { record ->
        val keys = when(record.kind) { "task" -> listOf("doDate", "deadline"); "project" -> listOf("targetDate"); else -> emptyList() }
        if(record.kind == "reminder") listOfNotNull(runCatching { java.time.Instant.ofEpochMilli(record.value("scheduledAt").toLong()).atZone(java.time.ZoneId.systemDefault()).toLocalDate() }.getOrNull())
        else keys.mapNotNull { key -> runCatching { LocalDate.parse(record.value(key)) }.getOrNull() }
    }.toSet() }
    var manualDate by rememberSaveable { mutableStateOf(false) }
    var agendaActions by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    CalendarMonth(selectedDay ?: LocalDate.now(), scheduledDays) { date = it.toString() }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(if(selectedDay != null) "Agenda · ${selectedDay.format(DateTimeFormatter.ofPattern("MMM d"))}" else "Agenda", modifier = Modifier.weight(1f), fontFamily = Inter, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Text)
        Box {
            IconButton(onClick = { focus.clearFocus(); agendaActions = true }, modifier = Modifier.size(48.dp).semantics { contentDescription = "Agenda actions" }) {
                Text("•••", fontFamily = Inter, fontSize = 14.sp, color = Secondary)
            }
            DropdownMenu(agendaActions, onDismissRequest = { agendaActions = false }) {
                DropdownMenuItem(text = { Text("Choose date") }, onClick = { agendaActions = false; showDatePicker(context, date) { date = it } })
                DropdownMenuItem(text = { Text("Open daily note") }, enabled = selectedDay != null, onClick = { agendaActions = false; act("") { open(repository.dailyNote(date)) } })
                DropdownMenuItem(text = { Text("Type date") }, onClick = { agendaActions = false; manualDate = true })
            }
        }
    }
    if(manualDate) {
        Field("Daily agenda · YYYY-MM-DD", date) { date = it }
        TextButton(onClick = { focus.clearFocus(); manualDate = false }) { Text("Hide date entry") }
    }
    val validDate = selectedDay != null
    if(!validDate) { Copy("Choose a valid date in YYYY-MM-DD format."); return }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        live.filter { it.kind == "task" && (it.value("doDate") == date || it.value("deadline") == date) || it.kind == "project" && it.value("targetDate") == date }
            .sortedWith(compareBy<Record> { it.createdAt }.thenBy { it.id }).forEach { record ->
            CalendarAgendaRow("Anytime", record.title(), calendarDateDetail(record, selectedDay!!)) { open(record) }
        }
        reminders.forEach { reminder ->
            val scheduled = java.time.Instant.ofEpochMilli(reminder.value("scheduledAt").toLong()).atZone(java.time.ZoneId.systemDefault())
            if(scheduled.toLocalDate().toString() == date) {
                val target = live.find { it.id == reminder.value("targetId") }
                val time = scheduled.format(DateTimeFormatter.ofPattern("h:mm a"))
                if(target != null) CalendarAgendaRow(time, target.title(), "Reminder") { open(target) }
                else if(reminder.value("type") == "inbox") CalendarAgendaRow(time, "Inbox review", "Reminder")
            }
        }
    }
}
