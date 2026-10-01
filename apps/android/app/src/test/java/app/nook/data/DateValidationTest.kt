package app.nook.data

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class DateValidationTest {
    private fun records(date: String): List<Record> {
        val payloads = listOf(
            "task" to wireJson.encodeToJsonElement(Task("Do", doDate = date)),
            "task" to wireJson.encodeToJsonElement(Task("Due", deadline = date)),
            "project" to wireJson.encodeToJsonElement(Project("Outcome", targetDate = date)),
            "dailyNote" to wireJson.encodeToJsonElement(DailyNote(date)),
            "recurrence" to wireJson.encodeToJsonElement(Recurrence("monthly", anchorDate = date)),
        )
        return payloads.mapIndexed { index, (kind, data) ->
            Record("date:$index", "local:dates", kind, data as JsonObject, createdAt = 1, updatedAt = 1, clientId = "client")
        }
    }

    @Test fun allWireDateFieldsAcceptRealFourDigitCalendarDays() {
        listOf("0000-01-01", "0099-12-31", "2024-02-29", "2026-09-30", "9999-12-31").forEach { date ->
            records(date).forEach { assertEquals(it, validate(it)) }
        }
    }

    @Test fun malformedAndImpossibleDatesCannotEnterAnyWireDateField() {
        listOf("", "2026-02-29", "2024-02-30", "2026-13-01", "2026-00-01", "2026-01-00",
            "2026-1-01", "2026-01-1", "+2026-01-01", "+10000-01-01", "-0001-01-01",
            "2026-09-30T00:00:00Z", " 2026-09-30").forEach { date ->
            records(date).forEach { record ->
                assertTrue("${record.kind} accepted $date", runCatching { validate(record) }.isFailure)
            }
        }
    }

    @Test fun optionalTaskAndProjectDatesRemainOptional() {
        records("2026-09-30").filter { it.kind == "task" || it.kind == "project" }.forEach { record ->
            val withoutDates = JsonObject(record.data.filterKeys { it !in setOf("doDate", "deadline", "targetDate") })
            assertEquals(withoutDates, validate(record.copy(data = withoutDates)).data)
        }
    }
}
