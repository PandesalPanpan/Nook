package app.nook.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

val wireJson = Json { encodeDefaults = true; explicitNulls = false }

@Serializable
data class Record(
    val id: String, val accountId: String, val kind: String, val data: JsonObject,
    val schemaVersion: Int = 1, val createdAt: Long, val updatedAt: Long,
    val clientId: String, val deleted: Boolean = false, val archived: Boolean = false,
)

fun compareVersions(a: Record, b: Record): Int {
    val deletion = a.deleted.compareTo(b.deleted)
    if (deletion != 0) return deletion
    val time = a.updatedAt.compareTo(b.updatedAt)
    return if (time != 0) time else a.clientId.compareTo(b.clientId)
}

@Serializable data class ClarificationDraft(val mode: String, val action: String = "", val noteTitle: String = "", val noteBody: String = "", val homeId: String? = null, val doDate: String? = null, val deadline: String? = null, val relatedIds: List<String> = emptyList())
@Serializable data class Capture(val body: String, val captureType: String = "text", val attachmentIds: List<String> = emptyList(), val originalBody: String? = null, val processedAt: Long? = null, val processedIds: List<String>? = null, val clarificationDraft: ClarificationDraft? = null)
@Serializable data class Note(val title: String = "", val body: String, val projectId: String? = null, val areaId: String? = null, val resourceId: String? = null, val attachmentIds: List<String> = emptyList(), val relatedIds: List<String>? = null, val sourceCaptureId: String? = null)
@Serializable data class Task(val title: String, val completed: Boolean = false, val doDate: String? = null, val deadline: String? = null, val reminderId: String? = null, val recurrenceId: String? = null, val projectId: String? = null, val areaId: String? = null, val parentTaskId: String? = null, val resourceId: String? = null, val relatedIds: List<String>? = null, val sourceCaptureId: String? = null)
@Serializable data class Project(val title: String, val outcome: String = "", val targetDate: String? = null, val progress: Double = 0.0, val areaId: String? = null, val nextActionId: String? = null)
@Serializable data class Area(val title: String, val responsibility: String = "", val standards: String = "")
@Serializable data class Resource(val title: String, val description: String = "", val url: String? = null, val projectId: String? = null, val areaId: String? = null)
@Serializable data class Attachment(val filename: String, val mimeType: String, val size: Long, val ownerId: String, val storagePath: String? = null)
@Serializable data class DailyNote(val date: String, val body: String = "", val relatedIds: List<String> = emptyList())
@Serializable data class Reminder(val targetId: String? = null, val scheduledAt: Long, val type: String, val skipIfInboxEmpty: Boolean = true)
@Serializable data class Recurrence(val frequency: String, val interval: Int = 1, val anchorDate: String)
@Serializable data class NoteLink(val sourceId: String, val targetId: String)
@Serializable data class AppSettings(val tipsEnabled: Boolean = true, val dismissedTips: List<String> = emptyList(), val dailyNotesEnabled: Boolean = false, val inboxReviewEnabled: Boolean = false)
@Serializable data class Profile(val displayName: String)
