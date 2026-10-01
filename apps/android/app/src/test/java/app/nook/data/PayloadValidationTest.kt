package app.nook.data

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class PayloadValidationTest {
    private fun record(kind: String, payload: String) = Record("record", "local:validation", kind,
        wireJson.parseToJsonElement(payload) as JsonObject, createdAt = 1, updatedAt = 1, clientId = "client")
    private fun rejected(record: Record) = assertTrue("Accepted ${record.kind}: ${record.data.keys}", runCatching { validate(record) }.isFailure)

    @Test fun relationshipIdsAndListsCannotCarryMalformedWireReferences() {
        val bases = listOf(
            record("note", """{"body":"","projectId":"valid","areaId":"valid","resourceId":"valid","attachmentIds":[]}"""),
            record("task", """{"title":"","projectId":"valid","areaId":"valid","parentTaskId":"valid","reminderId":"valid","recurrenceId":"valid"}"""),
            record("project", """{"title":"","areaId":"valid","nextActionId":"valid"}"""),
            record("attachment", """{"filename":"","mimeType":"","size":0,"ownerId":"valid"}"""),
            record("reminder", """{"scheduledAt":0,"type":"task","targetId":"valid"}"""),
            record("noteLink", """{"sourceId":"valid","targetId":"valid"}"""),
        )
        for (base in bases) {
            assertEquals(base, validate(base))
            for (field in base.data.keys.filter { it.endsWith("Id") }) for (bad in listOf("", "other/account", "x".repeat(129))) {
                rejected(base.copy(data = JsonObject(base.data + (field to JsonPrimitive(bad)))))
            }
        }
        for ((kind, field, payload) in listOf(
            Triple("capture", "attachmentIds", """{"body":""}"""),
            Triple("dailyNote", "relatedIds", """{"date":"2026-09-30"}"""))) {
            val base = record(kind, payload)
            for (ids in listOf(JsonArray(listOf(JsonPrimitive("bad/id"))), JsonArray(List(10001) { JsonPrimitive("valid") })))
                rejected(base.copy(data = JsonObject(base.data + (field to ids))))
        }
    }

    @Test fun sharedTextAndSettingsLimitsAreEnforcedWithoutTruncating() {
        val bases = listOf(
            Triple(record("area", """{"title":""}"""), "standards", 500000),
            Triple(record("project", """{"title":""}"""), "outcome", 500000),
            Triple(record("dailyNote", """{"date":"2026-09-30"}"""), "body", 500000),
            Triple(record("profile", """{"displayName":""}"""), "displayName", 256),
            Triple(record("attachment", """{"filename":"","mimeType":"","size":0,"ownerId":"valid"}"""), "mimeType", 256),
        )
        for ((base, field, limit) in bases) {
            val valid = base.copy(data = JsonObject(base.data + (field to JsonPrimitive("x".repeat(limit)))))
            assertEquals(valid, validate(valid))
            rejected(base.copy(data = JsonObject(base.data + (field to JsonPrimitive("x".repeat(limit + 1))))))
        }
        val settings = record("settings", """{}""")
        for (tips in listOf(JsonArray(List(1001) { JsonPrimitive("tip") }), JsonArray(listOf(JsonPrimitive("x".repeat(257))))))
            rejected(settings.copy(data = JsonObject(mapOf("dismissedTips" to tips))))
    }

    @Test fun timestampsStayWithinJavaScriptExactIntegerRange() {
        val base = record("reminder", """{"scheduledAt":9007199254740991,"type":"inbox"}""")
        assertEquals(base, validate(base))
        rejected(base.copy(data = JsonObject(base.data + ("scheduledAt" to JsonPrimitive(9007199254740992L)))))
        rejected(base.copy(updatedAt = 9007199254740992L))
        rejected(base.copy(createdAt = 9007199254740992L, updatedAt = 9007199254740992L))
    }
}
