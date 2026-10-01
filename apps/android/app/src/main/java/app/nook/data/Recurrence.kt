package app.nook.data

import androidx.room.withTransaction
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.security.MessageDigest

fun nextOccurrence(rule: Recurrence, after: LocalDate): LocalDate {
    val anchor = LocalDate.parse(rule.anchorDate)
    require(rule.interval in 1..1000)
    if(rule.frequency != "monthly") {
        require(rule.frequency in setOf("daily", "weekly"))
        val step = rule.interval.toLong() * if(rule.frequency == "weekly") 7 else 1
        return anchor.plusDays(maxOf(1, Math.floorDiv(ChronoUnit.DAYS.between(anchor, after), step) + 1) * step)
    }
    var count = maxOf(1, Math.floorDiv(ChronoUnit.MONTHS.between(anchor.withDayOfMonth(1), after.withDayOfMonth(1)), rule.interval.toLong()))
    while(true) {
        val next = anchor.plusMonths(count * rule.interval)
        if(next > after) return next
        count++
    }
}
fun recurringTask(task: Task, rule: Recurrence): Task {
    val previous = LocalDate.parse(task.doDate ?: task.deadline ?: rule.anchorDate)
    val next = nextOccurrence(rule, previous)
    val shift = ChronoUnit.DAYS.between(previous, next)
    return task.copy(completed = false, reminderId = null,
        doDate = task.doDate?.let { LocalDate.parse(it).plusDays(shift).toString() } ?: if(task.deadline == null) next.toString() else null,
        deadline = task.deadline?.let { LocalDate.parse(it).plusDays(shift).toString() })
}
fun occurrenceId(recurrenceId: String, date: String): String = "repeat-" + MessageDigest.getInstance("SHA-256")
    .digest("nook:recurrence:$recurrenceId:$date".toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

suspend fun NookRepository.setRecurrence(taskId: String, rule: Recurrence?) = db.withTransaction {
    val record = requireNotNull(get(taskId))
    require(record.kind == "task" && !record.deleted && !record.archived)
    val task = wireJson.decodeFromJsonElement(Task.serializer(), record.data)
    require(!task.completed) { "Choose an active task" }
    val existing = task.recurrenceId?.let { get(it) }
    val recurrenceId = if(rule == null) null else if(existing?.kind == "recurrence" && !existing.deleted) {
        update(existing.id) { it.copy(archived = false, data = wireJson.encodeToJsonElement(rule) as JsonObject) }.id
    } else create("recurrence", wireJson.encodeToJsonElement(rule) as JsonObject).id
    update(taskId) { it.copy(data = wireJson.encodeToJsonElement(task.copy(recurrenceId = recurrenceId)) as JsonObject) }
}
