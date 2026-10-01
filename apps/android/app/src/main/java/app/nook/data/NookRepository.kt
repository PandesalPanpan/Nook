package app.nook.data

import androidx.room.withTransaction
import java.util.UUID
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

class NookRepository(val db: NookDatabase, val accountId: String, val clientId: String, private val clock: () -> Long = System::currentTimeMillis) {
    suspend fun updateSettings(change: (AppSettings) -> AppSettings): Unit = db.withTransaction {
        val previous = currentSettings(dao.all(accountId).map { it.decode() })
        if(previous == null) create("settings", wireJson.encodeToJsonElement(change(AppSettings())) as JsonObject)
        else update(previous.id) { it.copy(data = wireJson.encodeToJsonElement(change(wireJson.decodeFromJsonElement(AppSettings.serializer(), it.data))) as JsonObject) }
        Unit
    }
    private val dao get() = db.records()
    fun observeAll() = dao.observeAll(accountId).map { rows -> rows.map { it.decode() } }
    fun observe(kind: String) = dao.observe(accountId, kind).map { rows -> rows.map { it.decode() } }
    suspend fun get(id: String) = dao.get(accountId, id)?.decode()
    internal suspend fun write(record: Record): Record {
        validate(record)
        require(record.accountId == accountId)
        val saved = if(record.kind == "note" && !record.deleted) {
            val notes = dao.byKinds(accountId, listOf("note")).map { it.decode() }.filter { !it.deleted && it.id != record.id } + record
            val body = record.data["body"]!!.jsonPrimitive.content
            record.copy(data = JsonObject(record.data + ("body" to JsonPrimitive(stabilizeNoteLinks(body, notes)))))
        } else record
        validate(saved)
        dao.put(saved.store())
        index(saved)
        dao.enqueue(OutboxEntry(accountId, saved.id, wireJson.encodeToString(saved)))
        return saved
    }
    private suspend fun index(record: Record) {
        dao.unindex(record.accountId, record.id)
        searchable(record)?.let { dao.index(SearchRow(record.accountId, record.id, it)) }
    }
    suspend fun create(kind: String, data: JsonObject, id: String = UUID.randomUUID().toString()): Record = db.withTransaction {
        require(get(id) == null) { "Record identity already exists" }
        val now = clock()
        val record = Record(id, accountId, kind, data, createdAt = now, updatedAt = now, clientId = clientId)
        write(record)
    }
    suspend fun capture(body: String, type: String = "text"): Record {
        require(body.isNotBlank()) { "Add a thought" }
        require(type in setOf("text", "task", "link", "image"))
        return create("capture", wireJson.encodeToJsonElement(Capture(body, type)) as JsonObject)
    }
    suspend fun capture(body: String, type: String, originals: List<OriginalInput>): Record = db.withTransaction {
        require(body.isNotBlank() || originals.isNotEmpty()) { "Add a thought or photo" }
        require(originals.size <= 20) { "Choose at most 20 attachments" }
        require(originals.sumOf { it.bytes.size.toLong() } <= 50 * 1024 * 1024) { "Capture attachments exceed 50 MB in total" }
        val capture = create("capture", wireJson.encodeToJsonElement(Capture(body, type)) as JsonObject)
        val attachmentIds = originals.map { original ->
            require(original.bytes.size <= 50 * 1024 * 1024) { "Attachment exceeds 50 MB" }
            val attachment = create("attachment", wireJson.encodeToJsonElement(Attachment(original.filename, original.mimeType, original.bytes.size.toLong(), capture.id)) as JsonObject)
            dao.putOriginal(OriginalFile(accountId, attachment.id, original.bytes))
            attachment.id
        }
        update(capture.id) { it.copy(data = wireJson.encodeToJsonElement(Capture(body, type, attachmentIds)) as JsonObject) }
    }
    suspend fun update(id: String, change: (Record) -> Record): Record = db.withTransaction {
        val previous = requireNotNull(get(id)) { "Record unavailable" }
        check(!previous.deleted) { "Record deleted" }
        val proposed = change(previous)
        val result = write(proposed.copy(id = previous.id, accountId = accountId, kind = previous.kind, schemaVersion = previous.schemaVersion,
            createdAt = previous.createdAt, updatedAt = maxOf(clock(), previous.updatedAt + 1), clientId = clientId))
        if(previous.kind == "task" && result.kind == "task" && !result.deleted && !result.archived) {
            val before = wireJson.decodeFromJsonElement(Task.serializer(), previous.data)
            val task = wireJson.decodeFromJsonElement(Task.serializer(), result.data)
            if(!before.completed && task.completed && task.recurrenceId != null) {
                val recurrence = get(task.recurrenceId)
                if(recurrence?.kind == "recurrence" && !recurrence.deleted && !recurrence.archived) {
                    val rule = wireJson.decodeFromJsonElement(Recurrence.serializer(), recurrence.data)
                    var next = recurringTask(task, rule)
                    val nextId = occurrenceId(recurrence.id, next.doDate ?: next.deadline!!)
                    if(get(nextId) == null) {
                        task.reminderId?.let { get(it) }?.let { reminder ->
                            if(reminder.kind == "reminder" && !reminder.deleted) {
                                val reminderData = wireJson.decodeFromJsonElement(Reminder.serializer(), reminder.data)
                                if(reminderData.type != "inbox") {
                                    val reminderId = occurrenceId(recurrence.id + "-reminder", next.doDate ?: next.deadline!!)
                                    val previousDate = java.time.LocalDate.parse(task.doDate ?: task.deadline ?: rule.anchorDate)
                                    val days = java.time.temporal.ChronoUnit.DAYS.between(previousDate, java.time.LocalDate.parse(next.doDate ?: next.deadline))
                                    val scheduled = java.time.Instant.ofEpochMilli(reminderData.scheduledAt).atZone(java.time.ZoneId.systemDefault()).plusDays(days).toInstant().toEpochMilli()
                                    if(get(reminderId) == null) write(reminder.copy(id = reminderId, archived = false, createdAt = result.updatedAt, updatedAt = result.updatedAt, clientId = clientId,
                                        data = wireJson.encodeToJsonElement(reminderData.copy(targetId = nextId, scheduledAt = scheduled)) as JsonObject))
                                    next = next.copy(reminderId = reminderId)
                                }
                            }
                        }
                        write(result.copy(id = nextId, data = wireJson.encodeToJsonElement(next) as JsonObject, createdAt = result.updatedAt))
                    }
                    task.projectId?.let { get(it) }?.let { project ->
                        if(project.kind == "project" && !project.deleted && project.data["nextActionId"] == JsonPrimitive(result.id)) update(project.id) {
                            it.copy(data = JsonObject(it.data + ("nextActionId" to JsonPrimitive(nextId))))
                        }
                    }
                }
            }
        }
        result
    }
    suspend fun delete(id: String): Record = db.withTransaction {
        val deleted = update(id) { it.copy(deleted = true) }
        if(deleted.kind == "attachment") dao.removeOriginal(accountId, id)
        dao.byKinds(accountId, listOf("attachment", "reminder")).map { it.decode() }.filter { !it.deleted &&
            (it.kind == "attachment" && it.data["ownerId"] == JsonPrimitive(id) || it.kind == "reminder" && it.data["targetId"] == JsonPrimitive(id))
        }.forEach { child ->
            update(child.id) { it.copy(deleted = true) }
            if(child.kind == "attachment") dao.removeOriginal(accountId, child.id)
        }
        deleted
    }
    suspend fun archive(id: String, archived: Boolean) = update(id) { it.copy(archived = archived) }
    suspend fun process(id: String, destination: String, projectId: String? = null): Record = db.withTransaction {
        val record = requireNotNull(get(id))
        check(record.kind == "capture" && !record.deleted) { "Capture unavailable" }
        val capture = wireJson.decodeFromJsonElement(Capture.serializer(), record.data)
        if(projectId != null) {
            require(destination in listOf("task", "note", "resource")) { "Only tasks, notes and resources belong to a project" }
            val project = get(projectId)
            require(project != null && project.kind == "project" && !project.deleted && !project.archived) { "Project unavailable" }
        }
        val payload = when (destination) {
            "task" -> wireJson.encodeToJsonElement(Task(capture.body))
            "note" -> wireJson.encodeToJsonElement(Note(body = capture.body, attachmentIds = capture.attachmentIds))
            "project" -> wireJson.encodeToJsonElement(Project(capture.body))
            "resource" -> wireJson.encodeToJsonElement(Resource(capture.body, url = if (capture.captureType == "link") capture.body else null))
            else -> error("Unsupported destination")
        }
        val result = create(destination, (payload as JsonObject).let { if(projectId == null) it else JsonObject(it + ("projectId" to JsonPrimitive(projectId))) })
        capture.attachmentIds.forEach { attachmentId -> update(attachmentId) { attachment ->
            val data = wireJson.decodeFromJsonElement(Attachment.serializer(), attachment.data)
            attachment.copy(data = wireJson.encodeToJsonElement(data.copy(ownerId = result.id)) as JsonObject)
        } }
        delete(id)
        result
    }
    suspend fun receive(remote: Record) = db.withTransaction {
        validate(remote)
        require(remote.accountId == accountId) { "Account mismatch" }
        val local = get(remote.id)
        if (local == null || compareVersions(remote, local) > 0) {
            dao.put(remote.store())
            index(remote)
            dao.acknowledge(accountId, remote.id)
        }
        if(remote.kind == "attachment" && remote.deleted) dao.removeOriginal(accountId, remote.id)
    }
    suspend fun mergeIntoAccount(target: String): NookRepository = db.withTransaction {
        require(accountId.startsWith("local:") && target.isNotBlank() && !target.startsWith("local:"))
        for (stored in dao.all(accountId)) {
            val migrated = stored.decode().copy(accountId = target)
            val existing = dao.get(target, stored.id)?.decode()
            if (existing == null || compareVersions(migrated, existing) > 0) {
                dao.put(migrated.store())
                index(migrated)
                dao.enqueue(OutboxEntry(target, migrated.id, wireJson.encodeToString(migrated)))
            }
            dao.removeForMigration(accountId, stored.id)
            dao.unindex(accountId, stored.id)
            dao.acknowledge(accountId, stored.id)
            dao.original(accountId, stored.id)?.let { original ->
                if (dao.original(target, stored.id) == null) dao.putOriginal(original.copy(accountId = target))
                dao.removeOriginal(accountId, stored.id)
            }
        }
        NookRepository(db, target, clientId, clock)
    }
}
