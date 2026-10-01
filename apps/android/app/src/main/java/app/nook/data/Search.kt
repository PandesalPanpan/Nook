package app.nook.data

import java.text.Normalizer
import java.util.Locale
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged

fun NookRepository.observeSearch(input: String) = db.invalidationTracker.createFlow("records", "search")
    .map { search(input) }.distinctUntilChanged()

fun searchable(record: Record): String? {
    if (record.deleted || record.kind !in setOf("capture", "note", "task", "project", "area", "resource", "dailyNote")) return null
    val fields = setOf("title", "body", "outcome", "responsibility", "standards", "description", "url", "date")
    return record.data.entries.filter { it.key in fields }.mapNotNull { it.value.jsonPrimitive.contentOrNull }.joinToString(" ")
}
fun searchExpression(query: String): String {
    val normalized = Normalizer.normalize(query, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
    return Regex("[\\p{L}\\p{N}]+").findAll(normalized).map { "\"${it.value}*\"" }.distinct().joinToString(" ")
}

suspend fun NookRepository.search(input: String, kind: String? = null, archived: Boolean? = null): List<Record> {
    val filters = mutableMapOf<String, String>()
    val text = Regex("\\b(project|area|before):(?:\"([^\"]+)\"|(\\S+))").replace(input) { match ->
        filters[match.groupValues[1]] = match.groupValues[2].ifEmpty { match.groupValues[3] }; ""
    }
    val expression = searchExpression(text)
    val candidates = if (expression.isEmpty()) db.records().searchableRecords(accountId) else db.records().search(accountId, expression)
    val all = if (filters.containsKey("project") || filters.containsKey("area")) db.records().all(accountId).map { it.decode() }.filter { !it.deleted } else emptyList()
    val references = listOf("project", "area").mapNotNull { context ->
        filters[context]?.let { name -> context to all.asSequence().filter {
            it.kind == context && (it.id == name || it.data["title"]?.jsonPrimitive?.contentOrNull?.equals(name, true) == true)
        }.map { it.id }.toSet() }
    }.toMap()
    val areaFor = areaResolver(all,accountId)
    val projectFor = projectResolver(all,accountId)
    return candidates.map { it.decode() }.filter { record ->
        if (kind != null && record.kind != kind || archived != null && record.archived != archived) return@filter false
        for (context in listOf("project", "area")) {
            val ids = references[context] ?: continue
            val related = if(context == "area") areaFor(record) else projectFor(record)
            if (record.id !in ids && related !in ids) return@filter false
        }
        filters["before"]?.let { before ->
            if (runCatching { java.time.LocalDate.parse(before) }.isFailure) return@filter false
            val date = listOf("doDate", "deadline", "targetDate", "date").firstNotNullOfOrNull { record.data[it]?.jsonPrimitive?.contentOrNull }
                ?: java.time.Instant.ofEpochMilli(record.createdAt).atZone(java.time.ZoneOffset.UTC).toLocalDate().toString()
            if (date >= before) return@filter false
        }
        true
    }.sortedByDescending { it.updatedAt }.take(100)
}
