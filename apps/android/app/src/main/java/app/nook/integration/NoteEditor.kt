package app.nook.integration

import android.widget.TextView
import android.text.method.LinkMovementMethod
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.res.ResourcesCompat
import app.nook.R
import app.nook.data.Record
import kotlinx.serialization.json.*
import io.noties.markwon.*
import io.noties.markwon.ext.tasklist.TaskListPlugin
import org.commonmark.node.Node
import org.commonmark.node.Link
import org.commonmark.node.Image
import org.commonmark.node.Code
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.parser.Parser

private fun Record.noteTitle() = data["title"]?.jsonPrimitive?.contentOrNull.orEmpty().ifBlank { "Untitled note" }
fun resolveNote(reference: String, notes: List<Record>): Record? {
    val key = reference.trim()
    return notes.find { it.id == key } ?: notes.filter { it.data["title"]?.jsonPrimitive?.contentOrNull?.trim()?.equals(key, true) == true }.singleOrNull()
}
/** Work on parsed text nodes so code samples and existing links remain untouched. */
fun resolveWikiLinks(node: Node, notes: List<Record>): Set<String> {
    val outgoing = mutableSetOf<String>()
    fun walk(parent: Node) {
        if(parent is Link || parent is Image || parent is Code || parent is FencedCodeBlock || parent is IndentedCodeBlock) return
        var child = parent.firstChild
        while(child != null) {
            val next = child.next
            if(child is org.commonmark.node.Text) {
                val text = child.literal
                var start = 0
                for(match in Regex("\\[\\[([^]\\n]+)]]").findAll(text)) {
                    val parts = match.groupValues[1].split('|', limit = 2)
                    val target = resolveNote(parts[0], notes) ?: continue
                    child.insertBefore(org.commonmark.node.Text(text.substring(start, match.range.first)))
                    val link = Link("nook-note:${target.id}", null)
                    link.appendChild(org.commonmark.node.Text(parts.getOrNull(1)?.trim()?.ifBlank { null } ?: target.noteTitle()))
                    child.insertBefore(link)
                    outgoing += target.id
                    start = match.range.last + 1
                }
                if(start > 0) { child.insertBefore(org.commonmark.node.Text(text.substring(start))); child.unlink() }
            } else walk(child)
            child = next
        }
    }
    walk(node)
    return outgoing
}
fun outgoingNotes(body: String, notes: List<Record>) = resolveWikiLinks(Parser.builder().build().parse(body), notes)

/** A final standalone wiki-link paragraph uses the source's tappable reference pill. */
internal fun trailingNoteLink(body: String, notes: List<Record>): Pair<Record, String>? {
    if(!Regex("\\[\\[([^]\\n]+)]]").matches(body.trimEnd().substringAfterLast('\n').trim())) return null
    return trailingNoteLink(Parser.builder().build().parse(body), notes)
}
private fun trailingNoteLink(node: Node, notes: List<Record>): Pair<Record, String>? {
    val paragraph = node.lastChild as? org.commonmark.node.Paragraph ?: return null
    val text = paragraph.firstChild as? org.commonmark.node.Text ?: return null
    if(text.next != null) return null
    val match = Regex("\\[\\[([^]\\n]+)]]").matchEntire(text.literal.trim()) ?: return null
    val parts = match.groupValues[1].split('|', limit = 2)
    val target = resolveNote(parts[0], notes) ?: return null
    return target to (parts.getOrNull(1)?.trim()?.ifBlank { null } ?: target.noteTitle())
}

fun formatSelection(value: TextFieldValue, command: String): TextFieldValue {
    val start = value.selection.min; val end = value.selection.max
    val selected = value.text.substring(start, end)
    val wrappers = mapOf("Bold" to Triple("**", "**", "bold text"), "Italic" to Triple("*", "*", "italic text"), "Code" to Triple("`", "`", "code"), "Link" to Triple("[", "](https://)", "link text"))
    wrappers[command]?.let { (prefix, suffix, placeholder) ->
        val text = selected.ifEmpty { placeholder }
        return TextFieldValue(value.text.substring(0, start) + prefix + text + suffix + value.text.substring(end), TextRange(start + prefix.length, start + prefix.length + text.length))
    }
    val lineStart = value.text.lastIndexOf('\n', start - 1) + 1
    val lineEnd = value.text.indexOf('\n', end).let { if(it < 0) value.text.length else it }
    val prefix = mapOf("Heading" to "## ", "List" to "- ", "Checklist" to "- [ ] ", "Quote" to "> ").getValue(command)
    val text = value.text.substring(lineStart, lineEnd).split('\n').joinToString("\n") { prefix + it }
    return TextFieldValue(value.text.substring(0, lineStart) + text + value.text.substring(lineEnd), TextRange(lineStart, lineStart + text.length))
}

@Composable fun NoteEditor(body: String, change: (String) -> Unit, notes: List<Record>, open: (Record) -> Unit,
    initialPreview: Boolean = false, onAttach: (() -> Unit)? = null, attachmentBusy: Boolean = false, footer: @Composable () -> Unit = {}) {
    var value by remember { mutableStateOf(TextFieldValue(body, TextRange(body.length))) }
    if(value.text != body) value = value.copy(text = body, selection = TextRange(body.length))
    var preview by remember { mutableStateOf(initialPreview) }
    var picker by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var linkSelection by remember { mutableStateOf(value) }
    var menuSelection by remember { mutableStateOf<TextFieldValue?>(null) }
    val currentNotes by rememberUpdatedState(notes)
    val currentOpen by rememberUpdatedState(open)
    val focus = LocalFocusManager.current
    val density = LocalDensity.current
    val trailingLink = remember(preview, body, notes) { if(preview) trailingNoteLink(body, notes) else null }
    Column(Modifier.fillMaxWidth().heightIn(min = if(preview) 384.dp else 220.dp)
        .background(Color(0xff221d1a), RoundedCornerShape(20.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
    if(!preview) OutlinedTextField(value, { value = it; change(it.text) }, label = { Text("Note body") }, modifier = Modifier.fillMaxWidth().heightIn(min = 220.dp), shape = RoundedCornerShape(16.dp))
    else AndroidView(modifier = Modifier.fillMaxWidth(), factory = { context -> TextView(context).apply {
        setTextColor(0xfff5eee7.toInt()); setLinkTextColor(0xff7ea7f3.toInt()); textSize = 14f
        typeface = ResourcesCompat.getFont(context, R.font.inter); setLineSpacing(0f, 1.4f)
        movementMethod = LinkMovementMethod.getInstance()
        tag = Markwon.builder(context).usePlugin(TaskListPlugin.create(0xff3270e6.toInt(), 0xff9c9187.toInt(), 0xfff5eee7.toInt()))
            .usePlugin(object: AbstractMarkwonPlugin() {
                override fun configureTheme(builder: io.noties.markwon.core.MarkwonTheme.Builder) {
                    builder.headingTextSizeMultipliers(floatArrayOf(1.5f, 15f / 14f, 1f, 1f, 1f, 1f)).headingBreakHeight(0)
                        .codeBackgroundColor(0xff342d28.toInt()).codeBlockBackgroundColor(0xff342d28.toInt())
                        .codeTextColor(0xfff5eee7.toInt()).codeBlockTextColor(0xfff5eee7.toInt())
                }
                override fun configureConfiguration(builder: MarkwonConfiguration.Builder) {
                    builder.linkResolver { view, link ->
                        if(link.startsWith("nook-note:")) currentNotes.find { it.id == link.removePrefix("nook-note:") }?.let(currentOpen)
                        else if(android.net.Uri.parse(link).scheme in setOf("http", "https", "mailto")) {
                            try { view.context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(link))) } catch(_: android.content.ActivityNotFoundException) { }
                        }
                    }
                }
            }).build()
    } }, update = { view ->
        view.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 14f * density.density * density.fontScale)
        val markwon = view.tag as Markwon
        val parsed = markwon.parse(body)
        if(trailingLink != null) parsed.lastChild.unlink()
        resolveWikiLinks(parsed, notes)
        markwon.setParsedMarkdown(view, markwon.render(parsed))
    })
    if(preview && trailingLink != null) Box(Modifier.heightIn(min = 48.dp).clickable(role = Role.Button) { focus.clearFocus(); open(trailingLink.first) }, contentAlignment = Alignment.CenterStart) {
        Text("↗ ${trailingLink.second}", fontFamily = FontFamily(Font(R.font.inter)), fontSize = 12.sp, lineHeight = 18.sp,
            color = Color(0xfff5eee7), modifier = Modifier.background(Color(0xff342d28), RoundedCornerShape(14.dp)).padding(horizontal = 12.dp, vertical = 5.dp))
    }
    footer()
    }
    NoteToolbar({ command -> value = formatSelection(menuSelection?.takeIf { it.text == value.text } ?: value, command); change(value.text); preview = false },
        { linkSelection = value; focus.clearFocus(); picker = true }, onAttach, attachmentBusy, preview,
        { focus.clearFocus(); preview = !preview }, { menuSelection = value }, { menuSelection = null })
    if(picker) AlertDialog(onDismissRequest = { picker = false }, title = { Text("Link note") }, text = {
        Column { OutlinedTextField(query, { query = it }, label = { Text("Search notes") }, singleLine = true)
            if(notes.isEmpty()) Text("Create another note to link it here.")
            LazyColumn(Modifier.heightIn(max = 320.dp)) { items(notes.filter { it.noteTitle().contains(query, true) }, key = { it.id }) { target -> TextButton(onClick = {
                val selection = if(linkSelection.text == value.text) linkSelection.selection else TextRange(value.text.length)
                val start = selection.min.coerceIn(0, value.text.length); val end = selection.max.coerceIn(start, value.text.length); val link = "[[${target.id}]]"
                value = TextFieldValue(value.text.substring(0, start) + link + value.text.substring(end), TextRange(start + link.length))
                change(value.text); picker = false; preview = false
            }) { Text(target.noteTitle()) } }
            }
        }
    }, confirmButton = { TextButton(onClick = { picker = false }) { Text("Done") } })
}

/** Source 2:536 pills sit inside separate 48 dp targets; wrapping preserves large-text access. */
@Composable private fun NoteToolbar(format: (String) -> Unit, linkNote: () -> Unit, attach: (() -> Unit)?, attachmentBusy: Boolean, preview: Boolean, togglePreview: () -> Unit,
    prepareMore: () -> Unit, closeMore: () -> Unit) {
    var more by remember { mutableStateOf(false) }
    FlowRow(Modifier.fillMaxWidth().background(Color(0xff342d28), RoundedCornerShape(18.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for((command, glyph) in listOf("Bold" to "B", "Italic" to "I", "List" to "•", "Checklist" to "☑"))
            NoteTool(glyph, command) { format(command) }
        NoteTool("Link note", "Link note", primary = true, click = linkNote)
        if(attach != null) NoteTool(if(attachmentBusy) "Adding…" else "Attach", "Attach", enabled = !attachmentBusy, click = attach)
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = togglePreview, modifier = Modifier.heightIn(min = 48.dp)) { Text(if(preview) "Edit note" else "Preview") }
        Box {
            NoteTool("•••", "More formatting") { prepareMore(); more = true }
            DropdownMenu(more, onDismissRequest = { more = false; closeMore() }) {
                for(command in listOf("Heading", "Quote", "Code", "Link")) DropdownMenuItem(text = { Text(if(command == "Link") "Web link" else command) },
                    onClick = { more = false; format(command); closeMore() })
            }
        }
    }
}

@Composable private fun NoteTool(label: String, description: String, primary: Boolean = false, enabled: Boolean = true, click: () -> Unit) {
    Box(Modifier.heightIn(min = 48.dp).widthIn(min = 48.dp).clickable(enabled = enabled, role = Role.Button, onClick = click)
        .semantics { contentDescription = description }, contentAlignment = Alignment.Center) {
        Text(label, fontFamily = FontFamily(Font(R.font.inter)), fontSize = 12.sp, lineHeight = 18.sp,
            color = if(primary) Color.White else Color(0xfff5eee7), modifier = Modifier.background(if(primary) Color(0xff3270e6) else Color(0xff221d1a), RoundedCornerShape(14.dp)).padding(horizontal = 12.dp, vertical = 5.dp))
    }
}
