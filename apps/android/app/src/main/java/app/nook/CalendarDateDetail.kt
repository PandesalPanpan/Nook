package app.nook

import app.nook.data.Record
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** Preserve the distinction between a day reserved for work and its deadline. */
internal fun calendarDateDetail(record: Record, selected: LocalDate): String {
    if (record.kind == "project") return "Target date"
    fun date(key: String) = record.data[key]?.jsonPrimitive?.contentOrNull?.let {
        runCatching { LocalDate.parse(it) }.getOrNull()
    }
    val doDate = date("doDate")
    val deadline = date("deadline")
    val format = DateTimeFormatter.ofPattern(if ((deadline ?: selected).year == selected.year) "MMM d" else "MMM d, uuuu")
    return listOfNotNull(
        if (doDate == selected) "Do date" else null,
        when { deadline == selected -> "Deadline"; doDate == selected && deadline != null -> "deadline ${deadline.format(format)}"; else -> null },
    ).joinToString(" · ")
}
