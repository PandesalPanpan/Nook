package app.nook.data

import androidx.room.withTransaction
import kotlinx.serialization.json.*

suspend fun NookRepository.attach(ownerId: String, originals: List<OriginalInput>) = db.withTransaction {
    require(originals.size <= 20 && originals.sumOf { it.bytes.size.toLong() } <= 50 * 1024 * 1024) { "Choose at most 20 attachments, up to 50 MB in total" }
    val owner = requireNotNull(get(ownerId)) { "Record unavailable" }
    require(!owner.deleted && owner.kind in setOf("note", "dailyNote", "project", "area", "resource", "task", "capture")) { "Record unavailable" }
    val ids = originals.map { original ->
        val attachment = create("attachment", wireJson.encodeToJsonElement(Attachment(original.filename, original.mimeType, original.bytes.size.toLong(), ownerId)) as JsonObject)
        db.records().putOriginal(OriginalFile(accountId, attachment.id, original.bytes))
        attachment.id
    }
    if(ids.isNotEmpty() && owner.kind in setOf("note", "capture")) update(ownerId) {
        val previous = it.data["attachmentIds"]?.jsonArray ?: JsonArray(emptyList())
        it.copy(data = JsonObject(it.data + ("attachmentIds" to JsonArray(previous + ids.map(::JsonPrimitive)))))
    }
}
