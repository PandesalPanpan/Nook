package app.nook

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import app.nook.ai.*
import app.nook.data.*
import app.nook.data.Record
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*

@Composable private fun AiText(text: String, modifier: Modifier = Modifier, style: androidx.compose.ui.text.TextStyle = LocalTextStyle.current) {
    Text(text, modifier, style = style, fontFamily = androidx.compose.ui.text.font.FontFamily(androidx.compose.ui.text.font.Font(R.font.inter)))
}
private class AiWork { var job: Job? = null; var active = true }

@Composable internal fun AiSettings(repository: NookRepository) {
    val context = LocalContext.current
    val store = remember(repository.accountId) { AiSettingsStore(context, repository.accountId) }
    val scope = rememberCoroutineScope()
    var saved by remember { mutableStateOf(AiConfiguration()) }
    var config by remember { mutableStateOf(AiConfiguration()) }
    var key by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(true) }
    LaunchedEffect(store) { try { saved = store.load(); config = saved } catch (_: Exception) { message = "AI settings could not be read. Save your provider again." } finally { busy = false } }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        AiText("Nook AI", style = MaterialTheme.typography.titleLarge)
        AiText("AI suggests. You decide.")
        AiText("Provider")
        listOf("off" to "Off", "openai" to "OpenAI", "deepseek" to "DeepSeek", "custom" to "Custom compatible API").forEach { (provider, label) ->
            TextButton(enabled = !busy, onClick = { config = aiDefaults(provider); key = ""; message = "" }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { AiText(if(config.provider == provider) "✓ $label" else label) }
        }
        if(config.provider != "off") {
            OutlinedTextField(config.model, { config = config.copy(model = it) }, label = { AiText("Model") }, enabled = !busy, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp))
            if(config.provider == "custom") OutlinedTextField(config.endpoint, { config = config.copy(endpoint = it) }, label = { AiText("Chat completions endpoint") }, enabled = !busy, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp))
            OutlinedTextField(key, { key = it }, label = { AiText("API key") }, placeholder = { AiText(if(saved.provider == config.provider && saved.endpoint == config.endpoint && saved.apiKey.isNotEmpty()) "Saved key · leave blank to keep" else "Paste your API key") }, visualTransformation = PasswordVisualTransformation(), enabled = !busy, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp))
        }
        NookButton(enabled = !busy, onClick = {
            busy = true
            scope.launch { try {
                val next = config.copy(apiKey = key.ifBlank { if(saved.provider == config.provider && saved.endpoint == config.endpoint) saved.apiKey else "" })
                store.save(next); saved = store.load(); config = saved; key = ""; message = "AI settings saved on this device"
            } catch(cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { message = "Could not save. Check the key, model and HTTPS endpoint." }
            finally { busy = false } }
        }, shape = RoundedCornerShape(30.dp), modifier = Modifier.heightIn(min = 48.dp)) { AiText("Save AI settings") }
        AiText("AI actions always require confirmation.", style = MaterialTheme.typography.titleSmall)
        AiText("Only the saved text you choose and destination titles are sent to your provider. Keys stay encrypted on this device, outside sync and exports. Turning AI off removes the saved key.")
        if(message.isNotEmpty()) AiText(message)
    }
}

@Composable internal fun AiAssist(repository: NookRepository, record: Record, records: List<Record>, provider: (AiConfiguration) -> AiProvider = { CompatibleAiProvider(it) }) {
    val context = LocalContext.current
    val store = remember(repository.accountId) { AiSettingsStore(context, repository.accountId) }
    val scope = rememberCoroutineScope()
    var config by remember { mutableStateOf(AiConfiguration()) }
    var proposal by remember(record.id) { mutableStateOf<AiProposal?>(null) }
    var selected by remember(record.id) { mutableStateOf<List<Int>>(emptyList()) }
    var busy by remember(record.id) { mutableStateOf(false) }
    var message by remember(record.id) { mutableStateOf("") }
    val work = remember(record.id) { AiWork() }
    LaunchedEffect(store) { try { config = store.load() } catch (_: Exception) { config = AiConfiguration() } }
    LaunchedEffect(record.id, record.updatedAt, record.clientId) { work.job?.cancelAndJoin(); proposal = null; selected = emptyList(); busy = false }
    DisposableEffect(work) { onDispose { work.active = false; work.job?.cancel() } }
    if(config.provider == "off" || record.deleted || record.archived || record.kind !in listOf("capture", "note", "task", "project")) return
    val operation = when(record.kind) { "capture" -> "organize"; "note" -> "summary"; else -> "actions" }
    Surface(color = Color(0xff221d1a), shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(color = if(operation == "organize") Color(0xff5c4db8) else Color(0xff3fae9b), contentColor = if(operation == "organize") MaterialTheme.colorScheme.onSurface else Color(0xff171412), shape = RoundedCornerShape(14.dp)) { AiText(if(operation == "actions") "BREAK DOWN" else operation.uppercase(), Modifier.padding(horizontal = 12.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium) }
            AiText("AI suggests. You decide.", style = MaterialTheme.typography.titleMedium)
            AiText("Request sends this item’s saved text to ${config.provider}. Nothing changes until you confirm.")
            NookButton(enabled = !busy, onClick = {
                busy = true; message = ""; proposal = null
                work.job = scope.launch { try {
                    val destinations = if(operation == "organize") records.filter { !it.deleted && !it.archived && it.kind in listOf("project", "area", "resource") }.take(100).map { AiDestination(it.id, it.kind, it.data["title"]?.jsonPrimitive?.content.orEmpty()) } else emptyList()
                    check(operation != "organize" || destinations.isNotEmpty()) { "Create a Project, Area or Resource first" }
                    val text = record.data["body"]?.jsonPrimitive?.content ?: listOfNotNull(record.data["title"]?.jsonPrimitive?.content, record.data["outcome"]?.jsonPrimitive?.content).joinToString("\n")
                    val result = provider(config).suggest(AiRequest(operation, text, destinations)); proposal = AiProposal(record, result, destination = if(result is AiSuggestion.Organize) records.find { it.id == result.destinationId } else null); selected = emptyList()
                } catch(cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { message = "AI could not return a valid suggestion. Check your provider settings and saved text." }
                finally { if(work.active) busy = false } }
            }, shape = RoundedCornerShape(30.dp), modifier = Modifier.heightIn(min = 48.dp)) { AiText(if(busy) "Working…" else when(operation) { "organize" -> "Suggest destination"; "summary" -> "Summarize note"; else -> "Suggest next actions" }) }
            proposal?.let { pending ->
                when(val suggestion = pending.suggestion) {
                    is AiSuggestion.Organize -> { AiText("Looks related to: " + records.find { it.id == suggestion.destinationId }?.data?.get("title")?.jsonPrimitive?.content.orEmpty()); AiText(suggestion.reason) }
                    is AiSuggestion.Summary -> AiText(suggestion.summary)
                    is AiSuggestion.Actions -> suggestion.actions.forEachIndexed { index, title -> Row { Checkbox(selected.contains(index), { checked -> selected = if(checked) selected + index else selected - index }, modifier = Modifier.semantics { contentDescription = title }); Column(Modifier.padding(top = 6.dp)) { AiText(title, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)); AiText("Suggested", style = MaterialTheme.typography.bodySmall) } } }
                }
                NookButton(enabled = !busy && (pending.suggestion !is AiSuggestion.Actions || selected.isNotEmpty()), onClick = {
                    busy = true
                    work.job = scope.launch { try { repository.applyProposal(pending, selected); proposal = null; message = "Accepted and saved locally" }
                    catch(cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { message = "This item or destination changed. Request a fresh suggestion." }
                    finally { if(work.active) busy = false } }
                }, shape = RoundedCornerShape(30.dp), modifier = Modifier.heightIn(min = 48.dp)) { AiText(when(pending.suggestion) { is AiSuggestion.Organize -> "Move as note"; is AiSuggestion.Summary -> "Save summary as note"; else -> "Add selected actions" }) }
                TextButton(enabled = !busy, onClick = { proposal = null }) { AiText("Dismiss") }
            }
            if(message.isNotEmpty()) AiText(message)
        }
    }
}
