package app.nook.data

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class NoteLinksTest {
    private fun note(id: String, title: String) = Record(id, "local:links", "note", wireJson.encodeToJsonElement(Note(title, "")) as JsonObject, createdAt = 1, updatedAt = 1, clientId = "client")
    @Test fun sourceTextAliasesAndUnicodeBindWhileCodeLinksImagesHtmlAndEscapesStayUntouched() {
        val target = note("target", "Garden")
        val body = "😀 [[Garden]] and [[Garden|Plants]]\n\n`[[Garden]]`\n\n```text\n[[Garden]]\n```\n\n    [[Garden]]\n\n[existing [[Garden]]](https://example.com)\n\n![[Garden]](image.png)\n\n<!-- [[Garden]] -->\n\n\\[[Garden]]\n\n[[Garden]](https://example.com)"
        val expected = body.replace("😀 [[Garden]] and [[Garden|Plants]]", "😀 [[target|Garden]] and [[target|Plants]]")
        assertEquals(expected, stabilizeNoteLinks(body, listOf(target)))
        assertEquals(expected, stabilizeNoteLinks(expected, listOf(target.copy(data = wireJson.encodeToJsonElement(Note("Renamed", "")) as JsonObject))))
    }
    @Test fun ambiguousUnresolvedAndExistingIdReferencesArePreserved() {
        val notes = listOf(note("a", "Duplicate"), note("b", "Duplicate"), note("target", "Garden"))
        val body = "[[Duplicate]] [[Missing]] [[target]] [[target|Plants]] [[**Garden**]]"
        assertEquals(body, stabilizeNoteLinks(body, notes))
    }
}
