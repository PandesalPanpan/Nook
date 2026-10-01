package app.nook.data

import kotlinx.serialization.json.*
import org.commonmark.node.*
import org.commonmark.parser.Parser

private val wikiPattern = Regex("\\[\\[([^]\\n]+)]]")
private fun resolveReference(reference: String, notes: List<Record>): Record? {
    val key = reference.trim()
    return notes.find { it.id == key } ?: notes.filter {
        it.data["title"]?.jsonPrimitive?.contentOrNull?.trim()?.equals(key, true) == true
    }.singleOrNull()
}
private fun textNodes(body: String, consume: (String) -> Unit) {
    fun walk(node: Node) {
        if(node is Link || node is Image || node is Code || node is FencedCodeBlock || node is IndentedCodeBlock || node is HtmlBlock || node is HtmlInline) return
        if(node is Text) consume(node.literal)
        var child = node.firstChild
        while(child != null) { walk(child); child = child.next }
    }
    walk(Parser.builder().build().parse(body))
}

/** Preserve Markdown source; only uniquely resolved title links in ordinary text are bound. */
fun stabilizeNoteLinks(body: String, notes: List<Record>): String {
    val eligible = mutableMapOf<String, Record>()
    textNodes(body) { text -> wikiPattern.findAll(text).forEach { match ->
        resolveReference(match.groupValues[1].split('|', limit = 2)[0], notes)?.let { eligible[match.value] = it }
    } }
    data class Edit(val start: Int, val end: Int, val marker: String, val probe: String, val replacement: String)
    var prefix = "NookWikiBindingToken"
    while(body.contains(prefix)) prefix += "X"
    val edits = wikiPattern.findAll(body).mapIndexedNotNull { index, match ->
        val target = eligible[match.value] ?: return@mapIndexedNotNull null
        val parts = match.groupValues[1].split('|', limit = 2)
        if(target.id == parts[0].trim()) return@mapIndexedNotNull null
        val slashes = body.substring(0, match.range.first).takeLastWhile { it == '\\' }.length
        if(slashes % 2 != 0) return@mapIndexedNotNull null
        val marker = "${prefix}${index}End"
        val label = parts.getOrNull(1)?.trim()?.ifBlank { null } ?: parts[0].trim()
        val probe = "[[$marker${parts.getOrNull(1)?.let { "|$it" }.orEmpty()}]]"
        Edit(match.range.first, match.range.last + 1, marker, probe, "[[${target.id}|$label]]")
    }.toList()
    if(edits.isEmpty()) return body
    var probe = body
    for(edit in edits.asReversed()) probe = probe.substring(0, edit.start) + edit.probe + probe.substring(edit.end)
    val visible = mutableSetOf<String>()
    val markerPattern = Regex("${prefix}[0-9]+End")
    textNodes(probe) { text -> markerPattern.findAll(text).forEach { visible += it.value } }
    var result = body
    for(edit in edits.asReversed()) if(edit.marker in visible) result = result.substring(0, edit.start) + edit.replacement + result.substring(edit.end)
    return result
}
