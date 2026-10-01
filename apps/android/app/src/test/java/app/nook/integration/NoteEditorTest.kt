package app.nook.integration

import app.nook.data.*
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import org.junit.Assert.*
import org.junit.Test

class NoteEditorTest {
    private fun note(id: String, title: String) = Record(id, "local:test", "note", wireJson.encodeToJsonElement(Note(title, "")) as JsonObject, createdAt = 1, updatedAt = 1, clientId = "test")
    @Test fun selectionFormattingPreservesOtherText() {
        val result = formatSelection(TextFieldValue("hello world", TextRange(6, 11)), "Bold")
        assertEquals("hello **world**", result.text); assertEquals(TextRange(8, 13), result.selection)
        assertEquals("- [ ] one\n- [ ] two\nthree", formatSelection(TextFieldValue("one\ntwo\nthree", TextRange(1, 6)), "Checklist").text)
    }
    @Test fun referencesResolveByIdOrUniqueTitleAndSkipMarkdownCode() {
        val notes = listOf(note("first", "Garden"), note("second", "Garden"))
        assertNull(resolveNote("Garden", notes)); assertEquals("first", resolveNote("first", notes)?.id)
        assertEquals(setOf("first"), outgoingNotes("[[first|Plants]]", notes))
        assertEquals(emptySet<String>(), outgoingNotes("`[[first]]`\n\n```\n[[first]]\n```\n\n    [[first]]\n\n[existing [[first]]](https://example.com)", notes))
    }
    @Test fun onlyStandaloneTrailingReferencesBecomePills() {
        val target = note("first", "Garden")
        val notes = listOf(target)
        assertEquals(target to "Plants", trailingNoteLink("Intro\n\n[[first|Plants]]", notes))
        assertEquals(target to "Garden", trailingNoteLink("[[Garden]]", notes))
        for(body in listOf("Intro [[first]]", "**[[first]]**", "`[[first]]`", "```\n[[first]]\n```", "    [[first]]", "> [[first]]", "- [[first]]", "[[missing]]", "[[first]]\n\nMore text", "\\[[first]]"))
            assertNull(body, trailingNoteLink(body, notes))
    }
}
