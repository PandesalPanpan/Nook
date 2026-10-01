package app.nook.data

import androidx.room.withTransaction
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import java.time.LocalDate
import java.time.DayOfWeek
import java.time.temporal.TemporalAdjusters

fun weekStart(date: String): String = LocalDate.parse(date).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toString()
suspend fun NookRepository.saveWeeklyFocus(date: String, body: String): Record = db.withTransaction {
    require(body.isNotBlank()) { "Choose one thing to focus on" }
    val week = weekStart(date)
    var id = "weekly-focus-$week"; var generation = 1
    var result: Record? = null
    while(result == null) {
        val existing = get(id)
        if(existing == null) result = create("note", wireJson.encodeToJsonElement(Note("Weekly focus · $week", body)) as JsonObject, id)
        else if(existing.kind == "note" && !existing.deleted) result = update(id) {
            val note = wireJson.decodeFromJsonElement(Note.serializer(), it.data)
            it.copy(data = wireJson.encodeToJsonElement(note.copy(body = body)) as JsonObject)
        }
        else id = "weekly-focus-$week-${++generation}"
    }
    result
}
