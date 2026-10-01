package app.nook.data

import androidx.room.withTransaction
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import java.time.LocalDate

suspend fun NookRepository.dailyNote(date: String): Record = db.withTransaction {
    require(Regex("\\d{4}-\\d{2}-\\d{2}").matches(date)) { "Choose a valid date" }
    LocalDate.parse(date)
    val existing = db.records().byKinds(accountId, listOf("dailyNote")).map { it.decode() }.filter {
        !it.deleted && wireJson.decodeFromJsonElement(DailyNote.serializer(), it.data).date == date
    }.sortedWith(compareByDescending<Record> { it.createdAt }.thenBy { it.id }).firstOrNull()
    if(existing != null) return@withTransaction existing
    var generation = 1; var id = "daily-$date"
    while(get(id) != null) id = "daily-$date-${++generation}"
    create("dailyNote", wireJson.encodeToJsonElement(DailyNote(date, "## Wins\n\n## Loose thoughts\n")) as JsonObject, id)
}
