package app.nook.data

import androidx.room.withTransaction
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull

data class OriginalInput(val filename: String, val mimeType: String, val bytes: ByteArray)
@Serializable private data class BackupManifest(val format: String = "nook", val version: Int = 1, val exportedAt: Long, val records: List<Record>)
private const val MAX_BACKUP = 250 * 1024 * 1024

suspend fun NookRepository.exportBackup(): ByteArray {
    val (records, originals) = db.withTransaction { db.records().all(accountId).map { it.decode() } to db.records().originals(accountId) }
    val bytes = ByteArrayOutputStream()
    ZipOutputStream(bytes).use { zip ->
        fun entry(path: String, value: ByteArray) { zip.putNextEntry(ZipEntry(path)); zip.write(value); zip.closeEntry() }
        entry("nook.json", wireJson.encodeToString(BackupManifest(exportedAt = System.currentTimeMillis(), records = records)).toByteArray(Charsets.UTF_8))
        for (record in records.filter { !it.deleted }) when(record.kind) {
            "note" -> {
                val note = wireJson.decodeFromJsonElement(Note.serializer(), record.data)
                entry("notes/${record.id}.md", ((if(note.title.isNotEmpty()) "# ${note.title}\n\n" else "") + note.body).toByteArray(Charsets.UTF_8))
            }
            "dailyNote" -> {
                val note = wireJson.decodeFromJsonElement(DailyNote.serializer(), record.data)
                entry("daily/${note.date}-${record.id}.md", note.body.toByteArray(Charsets.UTF_8))
            }
            "attachment" -> {
                val original = originals.find { it.id == record.id } ?: error("Original attachment unavailable. Download it before exporting.")
                entry("attachments/${record.id}", original.bytes)
            }
        }
    }
    return bytes.toByteArray()
}

/** Validate the entire archive before a single transaction touches the local account. */
suspend fun NookRepository.restoreBackup(bytes: ByteArray): Int {
    require(bytes.size <= MAX_BACKUP) { "Backup exceeds 250 MB" }
    val entries = mutableMapOf<String, ByteArray>()
    var total = 0L
    ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
        while(true) {
            val entry = zip.nextEntry ?: break
            val name = entry.name
            require(!name.startsWith('/') && !name.contains('\\') && ".." !in name.split('/') && name !in entries) { "Unsafe backup paths" }
            require(entries.size < 100001) { "Too many backup entries" }
            val output = ByteArrayOutputStream(); val buffer = ByteArray(8192)
            while(true) { val count = zip.read(buffer); if(count < 0) break; total += count; require(total <= MAX_BACKUP) { "Backup exceeds 250 MB" }; output.write(buffer, 0, count) }
            entries[name] = output.toByteArray(); zip.closeEntry()
        }
    }
    val manifest = wireJson.decodeFromString<BackupManifest>(requireNotNull(entries["nook.json"]) { "Nook manifest missing" }.toString(Charsets.UTF_8))
    require(manifest.format == "nook" && manifest.version == 1 && manifest.records.size <= 100000) { "Unsupported backup format" }
    require(manifest.records.map { it.id }.toSet().size == manifest.records.size) { "Duplicate record IDs" }
    manifest.records.forEach { record ->
        validate(record)
        if(record.kind == "attachment" && !record.deleted) {
            val attachment = wireJson.decodeFromJsonElement(Attachment.serializer(), record.data)
            require(attachment.size <= 50 * 1024 * 1024 && entries["attachments/${record.id}"]?.size?.toLong() == attachment.size) { "Missing or invalid original attachment" }
        }
    }
    return db.withTransaction {
        var imported = 0
        for(source in manifest.records) {
            val record = source.copy(accountId = accountId)
            val existing = get(record.id)
            if(existing == null || compareVersions(record, existing) > 0) { write(record); imported++ }
            if(record.kind == "attachment" && !record.deleted && existing?.deleted != true && db.records().original(accountId, record.id) == null)
                db.records().putOriginal(OriginalFile(accountId, record.id, entries.getValue("attachments/${record.id}")))
        }
        imported
    }
}
