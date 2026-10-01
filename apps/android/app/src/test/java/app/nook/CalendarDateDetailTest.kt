package app.nook

import app.nook.data.*
import kotlinx.serialization.json.*
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class CalendarDateDetailTest {
    private val selected = LocalDate.of(2026, 9, 30)
    private fun task(doDate: String? = "2026-09-30", deadline: String? = null) = Record(
        "task", "local:calendar", "task", wireJson.encodeToJsonElement(Task("Work", doDate = doDate, deadline = deadline)) as JsonObject,
        createdAt = 1, updatedAt = 1, clientId = "test",
    )
    @Test fun doDateIncludesSeparateDeadlineWithoutMovingIt() {
        assertEquals("Do date · deadline Oct 2", calendarDateDetail(task(deadline = "2026-10-02"), selected))
        assertEquals("Do date · deadline Jan 2, 2027", calendarDateDetail(task(deadline = "2027-01-02"), selected))
        assertEquals("Deadline", calendarDateDetail(task(deadline = "2026-10-02"), selected.plusDays(2)))
    }
    @Test fun sameDayAndInvalidOptionalDatesStayDistinct() {
        assertEquals("Do date · Deadline", calendarDateDetail(task(deadline = "2026-09-30"), selected))
        assertEquals("Do date", calendarDateDetail(task(deadline = "2026-02-30"), selected))
        assertEquals("Deadline", calendarDateDetail(task(null, "2026-09-30"), selected))
    }
}
