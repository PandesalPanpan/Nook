package app.nook.data

import org.junit.Assert.*
import org.junit.Test

class SchemaTest {
    @Test fun sharedWireFixturePreservesIndependentDates() {
        val text = requireNotNull(javaClass.classLoader?.getResourceAsStream("task.json")).bufferedReader().use { it.readText() }
        val record = validate(wireJson.decodeFromString<Record>(text))
        val task = wireJson.decodeFromJsonElement(Task.serializer(), record.data)
        assertEquals("2026-09-30", task.doDate)
        assertEquals("2026-10-02", task.deadline)
        assertEquals("shared-project", task.projectId)
        assertEquals("parent-task", task.parentTaskId)
    }
    @Test fun deletionWinsOverClockSkew() {
        val text = requireNotNull(javaClass.classLoader?.getResourceAsStream("task.json")).bufferedReader().use { it.readText() }
        val record = wireJson.decodeFromString<Record>(text)
        assertTrue(compareVersions(record.copy(deleted = true), record.copy(updatedAt = Long.MAX_VALUE)) > 0)
    }
}
