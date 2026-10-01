package app.nook.ai

import androidx.room.withTransaction
import app.nook.data.*
import app.nook.data.Record
import kotlinx.serialization.json.*
import java.util.UUID

data class AiProposal(val source: Record, val suggestion: AiSuggestion, val id: String = UUID.randomUUID().toString(), val destination: Record? = null)
suspend fun NookRepository.applyProposal(proposal: AiProposal, selected: List<Int> = emptyList()) = db.withTransaction {
    val source = get(proposal.source.id)
    check(proposal.source.accountId == accountId && source != null && !source.deleted && !source.archived && compareVersions(source, proposal.source) == 0) { "This item changed. Request a fresh suggestion." }
    when(val suggestion = proposal.suggestion) {
        is AiSuggestion.Organize -> {
            check(source.kind == "capture") { "Choose an Inbox capture" }
            val target = get(suggestion.destinationId)
            check(target != null && !target.deleted && !target.archived && target.kind in listOf("project", "area", "resource")) { "Destination unavailable" }
            check(proposal.destination != null && proposal.destination.accountId == accountId && proposal.destination.id == target.id && compareVersions(target, proposal.destination) == 0) { "Destination changed. Request a fresh suggestion." }
            val note = process(source.id, "note")
            update(note.id) { it.copy(data = JsonObject(it.data + ("${target.kind}Id" to JsonPrimitive(target.id)))) }
        }
        is AiSuggestion.Summary -> {
            check(source.kind == "note") { "Choose a note" }
            val note = wireJson.decodeFromJsonElement(Note.serializer(), source.data)
            create("note", wireJson.encodeToJsonElement(Note("Summary · ${note.title.ifBlank { "Note" }}", "${suggestion.summary}\n\nSource: [[${source.id}]]", note.projectId, note.areaId, note.resourceId)) as JsonObject, proposal.id)
        }
        is AiSuggestion.Actions -> {
            check(source.kind in listOf("project", "task")) { "Choose a project or task" }
            val indices = selected.distinct(); require(indices.isNotEmpty() && indices.all { it in suggestion.actions.indices }) { "Select suggested actions" }
            fun optional(key: String) = source.data[key]?.jsonPrimitive?.contentOrNull
            indices.forEach { index ->
                val task = Task(suggestion.actions[index], projectId = if(source.kind == "project") source.id else optional("projectId"), areaId = optional("areaId"), parentTaskId = if(source.kind == "task") source.id else null)
                create("task", wireJson.encodeToJsonElement(task) as JsonObject, "${proposal.id}:$index")
            }
        }
    }
    Unit
}
