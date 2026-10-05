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
        val extensions = when(record.kind) {
            "capture" -> setOf("originalBody", "processedAt", "processedIds", "clarificationDraft")
            "note" -> setOf("relatedIds", "sourceCaptureId")
            "task" -> setOf("resourceId", "relatedIds", "sourceCaptureId")
            else -> emptySet()
        }
        val versioned = if(record.data.keys.any { it in extensions }) record.copy(schemaVersion = 2) else record
        validate(versioned)
        require(versioned.accountId == accountId)
        val saved = if(versioned.kind == "note" && !versioned.deleted) {
            val notes = dao.byKinds(accountId, listOf("note")).map { it.decode() }.filter { !it.deleted && it.id != versioned.id } + versioned
            val body = versioned.data["body"]!!.jsonPrimitive.content
            versioned.copy(data = JsonObject(versioned.data + ("body" to JsonPrimitive(stabilizeNoteLinks(body, notes)))))
        } else versioned
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
    suspend fun create(kind: String, data: JsonObject, id: String = UUID.randomUUID().toString(), schemaVersion: Int = 1): Record = db.withTransaction {
        require(get(id) == null) { "Record identity already exists" }
        val now = clock()
        val record = Record(id, accountId, kind, data, schemaVersion = schemaVersion, createdAt = now, updatedAt = now, clientId = clientId)
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
        val result = write(proposed.copy(id = previous.id, accountId = accountId, kind = previous.kind, schemaVersion = maxOf(previous.schemaVersion, proposed.schemaVersion),
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
    suspend fun saveClarificationDraft(id: String, body: String, draft: ClarificationDraft): Record = update(id) { current ->
        require(current.kind == "capture" && !current.deleted) { "Capture unavailable" }
        val capture = wireJson.decodeFromJsonElement(Capture.serializer(), current.data)
        current.copy(schemaVersion = 2, data = wireJson.encodeToJsonElement(capture.copy(
            body = body,
            originalBody = capture.originalBody ?: capture.body.takeIf { body != it },
            clarificationDraft = draft,
        )) as JsonObject)
    }

    suspend fun clarify(
        id: String, body: String, mode: String, action: String = "", noteTitle: String = "", noteBody: String = body,
        homeId: String? = null, doDate: String? = null, deadline: String? = null, relatedIds: List<String> = emptyList(),
    ): List<Record> = db.withTransaction {
        val source = requireNotNull(get(id)) { "Capture unavailable" }
        check(source.kind == "capture" && !source.deleted) { "Capture unavailable" }
        val capture = wireJson.decodeFromJsonElement(Capture.serializer(), source.data)
        if(capture.processedAt != null) {
            val existing = capture.processedIds.orEmpty().mapNotNull { resultId -> get(resultId)?.takeIf { !it.deleted } }
            check(existing.isNotEmpty()) { "This capture was already processed and its results are unavailable" }
            return@withTransaction existing
        }
        require(body.isNotBlank() || capture.attachmentIds.isNotEmpty()) { "Add a thought before saving" }
        require(mode in setOf("task", "note", "split")) { "Choose Task, Note, or Split" }
        if(mode == "split") require(action.isNotBlank()) { "Add an action for the linked Task" }
        val home = homeId?.let { homeKey ->
            val target = requireNotNull(get(homeKey)) { "Choose an active Project, Area, or Resource" }
            require(target.accountId == accountId && !target.deleted && !target.archived && target.kind in setOf("project", "area", "resource")) { "Choose an active Project, Area, or Resource" }
            target
        }
        val cleanRelated = relatedIds.distinct()
        cleanRelated.forEach { relatedId ->
            require(relatedId != id) { "An item cannot be connected to itself" }
            val target = get(relatedId)
            require(target != null && target.accountId == accountId && !target.deleted && !target.archived && target.kind in setOf("task", "note", "dailyNote", "project", "area", "resource")) { "A connected item is unavailable" }
        }
        val homeField = when(home?.kind) { "project" -> "projectId"; "area" -> "areaId"; "resource" -> "resourceId"; else -> null }
        val homeData = if(home != null && homeField != null) mapOf(homeField to JsonPrimitive(home.id)) else emptyMap()
        val allResults = mutableListOf<Record>()
        fun resultId(role: String) = "clarify-${source.id}-$role"
        suspend fun <T> createOrReuse(kind: String, resultId: String, payload: T, serializer: kotlinx.serialization.KSerializer<T>): Record {
            val previous = get(resultId)
            if(previous != null) {
                check(previous.kind == kind && previous.data["sourceCaptureId"] == JsonPrimitive(source.id) && !previous.deleted) { "A previous clarification result is unavailable; it was not recreated" }
                return previous
            }
            val data = wireJson.encodeToJsonElement(serializer, payload) as JsonObject
            return create(kind, data, resultId, 2)
        }
        if(mode == "task") {
            val taskData = wireJson.encodeToJsonElement(Task(body.trim().ifBlank { "Review photo capture" }, doDate = doDate?.takeIf { it.isNotBlank() }, deadline = deadline?.takeIf { it.isNotBlank() }, resourceId = home?.id?.takeIf { home.kind == "resource" }, relatedIds = cleanRelated, sourceCaptureId = source.id)) as JsonObject
            allResults += createOrReuse("task", resultId("task"), JsonObject(taskData + homeData), JsonObject.serializer())
        } else {
            val title = noteTitle.trim().ifBlank { body.lineSequence().firstOrNull()?.trim().orEmpty().ifBlank { body.trim().ifBlank { "Photo capture" } } }.take(10000)
            val linkedTaskId = resultId("task")
            val noteLinks = (cleanRelated + if(mode == "split") listOf(linkedTaskId) else emptyList()).distinct()
            val noteData = wireJson.encodeToJsonElement(Note(title, noteBody, resourceId = home?.id?.takeIf { home.kind == "resource" }, attachmentIds = capture.attachmentIds, relatedIds = noteLinks, sourceCaptureId = source.id)) as JsonObject
            allResults += createOrReuse("note", resultId("note"), JsonObject(noteData + homeData), JsonObject.serializer())
            if(mode == "split") {
                val taskLinks = (cleanRelated + resultId("note")).distinct()
                val taskData = wireJson.encodeToJsonElement(Task(action.trim().ifBlank { "Review photo capture" }, doDate = doDate?.takeIf { it.isNotBlank() }, deadline = deadline?.takeIf { it.isNotBlank() }, resourceId = home?.id?.takeIf { home.kind == "resource" }, relatedIds = taskLinks, sourceCaptureId = source.id)) as JsonObject
                allResults += createOrReuse("task", linkedTaskId, JsonObject(taskData + homeData), JsonObject.serializer())
            }
        }
        val noteId = allResults.firstOrNull { it.kind == "note" }?.id
        for(attachmentId in capture.attachmentIds) {
            val attachment = get(attachmentId)
            if(attachment?.kind == "attachment" && !attachment.deleted) update(attachmentId) { current ->
                val data = wireJson.decodeFromJsonElement(Attachment.serializer(), current.data)
                current.copy(data = wireJson.encodeToJsonElement(data.copy(ownerId = noteId ?: allResults.first().id)) as JsonObject)
            }
        }
        val processedAt = maxOf(clock(), source.createdAt)
        update(id) { current ->
            val latest = wireJson.decodeFromJsonElement(Capture.serializer(), current.data)
            current.copy(schemaVersion = 2, data = wireJson.encodeToJsonElement(latest.copy(
                body = body, originalBody = latest.originalBody ?: latest.body, processedAt = processedAt,
                processedIds = allResults.map { it.id }, clarificationDraft = null,
            )) as JsonObject)
        }
        allResults
    }

    suspend fun process(id: String, destination: String, projectId: String? = null): Record {
        if(destination == "task" || destination == "note") {
            val source = requireNotNull(get(id)) { "Capture unavailable" }
            check(source.kind == "capture" && !source.deleted) { "Capture unavailable" }
            val capture = wireJson.decodeFromJsonElement(Capture.serializer(), source.data)
            return clarify(id, capture.body, destination, homeId = projectId).first()
        }
        return db.withTransaction {
            val source = requireNotNull(get(id)) { "Capture unavailable" }
            check(source.kind == "capture" && !source.deleted) { "Capture unavailable" }
            val capture = wireJson.decodeFromJsonElement(Capture.serializer(), source.data)
            if(capture.processedAt != null) {
                val existing = capture.processedIds.orEmpty().mapNotNull { get(it)?.takeIf { record -> !record.deleted } }.firstOrNull()
                return@withTransaction requireNotNull(existing) { "This capture was already processed and its results are unavailable" }
            }
            require(destination in setOf("project", "resource")) { "Unsupported destination" }
            val recordId = "clarify-${source.id}-$destination"
            val payload: JsonObject = if(destination == "project") wireJson.encodeToJsonElement(Project(capture.body)) as JsonObject
                else wireJson.encodeToJsonElement(Resource(capture.body, url = if(capture.captureType == "link") capture.body else null)) as JsonObject
            val result = get(recordId)?.also { check(it.kind == destination && !it.deleted) { "A previous clarification result is unavailable; it was not recreated" } }
                ?: create(destination, payload, recordId)
            capture.attachmentIds.forEach { attachmentId -> update(attachmentId) { attachment ->
                val data = wireJson.decodeFromJsonElement(Attachment.serializer(), attachment.data)
                attachment.copy(data = wireJson.encodeToJsonElement(data.copy(ownerId = result.id)) as JsonObject)
            } }
            val processedAt = maxOf(clock(), source.createdAt)
            update(id) { current ->
                val latest = wireJson.decodeFromJsonElement(Capture.serializer(), current.data)
                current.copy(schemaVersion = 2, data = wireJson.encodeToJsonElement(latest.copy(originalBody = latest.originalBody ?: latest.body, processedAt = processedAt, processedIds = listOf(result.id), clarificationDraft = null)) as JsonObject)
            }
            result
        }
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
