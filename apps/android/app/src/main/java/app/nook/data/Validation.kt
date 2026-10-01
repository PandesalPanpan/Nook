package app.nook.data

import kotlinx.serialization.json.*

private const val MAX_WIRE_INTEGER = 9_007_199_254_740_991L
private val wireIdentity = Regex("^[A-Za-z0-9:_-]{1,128}$")

/** Bounds shared with the web schema, before a local write or imported/remote record is accepted. */
private fun validatePayloadBounds(data: JsonObject) {
    val lengths = mapOf("body" to 500000, "outcome" to 500000, "responsibility" to 500000,
        "standards" to 500000, "description" to 500000, "title" to 10000,
        "filename" to 10000, "url" to 10000, "mimeType" to 256,
        "storagePath" to 1024, "displayName" to 256)
    for ((field, limit) in lengths) data[field]?.let { value ->
        require(value is JsonPrimitive && value.isString && value.content.length <= limit) { "Invalid $field" }
    }
    for (field in listOf("projectId", "areaId", "resourceId", "parentTaskId", "reminderId",
        "recurrenceId", "nextActionId", "ownerId", "sourceId", "targetId")) data[field]?.let { value ->
        require(value is JsonPrimitive && value.isString && wireIdentity.matches(value.content)) { "Invalid $field" }
    }
    for (field in listOf("attachmentIds", "relatedIds")) data[field]?.let { value ->
        require(value is JsonArray && value.size <= 10000 && value.all {
            it is JsonPrimitive && it.isString && wireIdentity.matches(it.content)
        }) { "Invalid $field" }
    }
    data["dismissedTips"]?.let { value ->
        require(value is JsonArray && value.size <= 1000 && value.all {
            it is JsonPrimitive && it.isString && it.content.length <= 256
        }) { "Invalid dismissedTips" }
    }
}

/** Shared wire dates are real calendar days in the four-digit YYYY-MM-DD range. */
private fun validateDate(value: String) {
    require(Regex("^[0-9]{4}-[0-9]{2}-[0-9]{2}$").matches(value)) { "Use a valid YYYY-MM-DD date" }
    java.time.LocalDate.parse(value)
}

/** Validate transport/import data at the domain boundary, never coerce unknown fields. */
fun validate(record: Record): Record {
    val identity = wireIdentity
    require(identity.matches(record.id) && identity.matches(record.accountId) && identity.matches(record.clientId))
    require(record.schemaVersion == 1 && record.createdAt in 0..MAX_WIRE_INTEGER && record.updatedAt in record.createdAt..MAX_WIRE_INTEGER)
    val data = record.data
    validatePayloadBounds(data)
    when (record.kind) {
        "capture" -> wireJson.decodeFromJsonElement(Capture.serializer(), data).also {
            require(it.captureType in setOf("text", "task", "link", "image")); require(it.body.length <= 500000)
        }
        "note" -> wireJson.decodeFromJsonElement(Note.serializer(), data).also { require(it.body.length <= 500000 && it.title.length <= 10000) }
        "task" -> wireJson.decodeFromJsonElement(Task.serializer(), data).also {
            require(it.title.length <= 10000)
            it.doDate?.let(::validateDate); it.deadline?.let(::validateDate)
        }
        "project" -> wireJson.decodeFromJsonElement(Project.serializer(), data).also { require(it.progress in 0.0..100.0 && it.title.length <= 10000); it.targetDate?.let(::validateDate) }
        "area" -> wireJson.decodeFromJsonElement(Area.serializer(), data)
        "resource" -> wireJson.decodeFromJsonElement(Resource.serializer(), data).also {
            require(it.title.length <= 10000 && it.description.length <= 500000 && (it.url?.length ?: 0) <= 10000)
            it.projectId?.let { id -> require(identity.matches(id)) }; it.areaId?.let { id -> require(identity.matches(id)) }
        }
        "attachment" -> wireJson.decodeFromJsonElement(Attachment.serializer(), data).also { require(it.size in 0..50L * 1024 * 1024) }
        "dailyNote" -> wireJson.decodeFromJsonElement(DailyNote.serializer(), data).also { validateDate(it.date) }
        "reminder" -> wireJson.decodeFromJsonElement(Reminder.serializer(), data).also { require(it.scheduledAt in 0..MAX_WIRE_INTEGER && it.type in setOf("task", "deadline", "inbox")) }
        "recurrence" -> wireJson.decodeFromJsonElement(Recurrence.serializer(), data).also { require(it.interval in 1..1000 && it.frequency in setOf("daily", "weekly", "monthly")); validateDate(it.anchorDate) }
        "noteLink" -> wireJson.decodeFromJsonElement(NoteLink.serializer(), data)
        "settings" -> wireJson.decodeFromJsonElement(AppSettings.serializer(), data)
        "profile" -> wireJson.decodeFromJsonElement(Profile.serializer(), data)
        else -> error("Unsupported record kind")
    }
    return record
}
