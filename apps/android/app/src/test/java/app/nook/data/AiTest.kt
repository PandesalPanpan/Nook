package app.nook.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.nook.ai.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class AiTest {
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), NookDatabase::class.java).build()
    private val repository = NookRepository(db, "local:ai", "client")
    @After fun close() { db.close() }
    private val config = aiDefaults("openai").copy(apiKey = "fake-device-key")
    @Test fun offNeverCallsNetworkAndProviderErrorsAreRedacted() = runTest {
        var calls = 0
        val http = AiHttp { _, _, _ -> calls++; error("fake-device-key") }
        try { CompatibleAiProvider(AiConfiguration(), http).suggest(AiRequest("summary", "Note")); fail("Off must reject") } catch (_: IllegalStateException) { }
        assertEquals(0, calls)
        try { CompatibleAiProvider(config, http).suggest(AiRequest("summary", "Note")); fail("Expected failure") } catch(error: IllegalStateException) { assertFalse(error.message!!.contains("fake-device-key")); assertNull(error.cause) }
        assertFalse(config.toString().contains("fake-device-key"))
    }
    @Test fun unknownDestinationsUnsafeEndpointsAndIncompleteResponsesAreRejected() = runTest {
        val request = AiRequest("organize", "Research", listOf(AiDestination("garden", "project", "Garden")))
        try { parseSuggestion("""{"kind":"organize","destinationId":"unknown","reason":"Reference"}""", request); fail("Unknown destination") } catch (_: IllegalArgumentException) { }
        try { validateAiConfiguration(config.copy(provider = "custom", endpoint = "http://unsafe.example")); fail("HTTPS required") } catch (_: IllegalArgumentException) { }
        val provider = CompatibleAiProvider(config, AiHttp { _, _, _ -> """{"choices":[{"finish_reason":"length","message":{"content":"{}"}}]}""" })
        try { provider.suggest(request); fail("Truncated completion") } catch (_: IllegalStateException) { }
    }
    @Test fun enabledProvidersSendValidRequestsAndParseSuccessfulResponses() = runTest {
        for(name in listOf("openai", "deepseek", "custom")) {
            val configuration = if(name == "custom") AiConfiguration(name, "https://compatible.example/v1/chat/completions", "custom-model", "fake-local-key") else aiDefaults(name).copy(apiKey = "fake-local-key")
            val provider = CompatibleAiProvider(configuration, AiHttp { endpoint, key, body ->
                assertEquals(configuration.endpoint, endpoint); assertEquals("fake-local-key", key); assertFalse(body.contains("fake-local-key"))
                val request = wireJson.parseToJsonElement(body).jsonObject
                assertEquals(configuration.model, request["model"]!!.jsonPrimitive.content)
                assertEquals("json_object", request["response_format"]!!.jsonObject["type"]!!.jsonPrimitive.content)
                assertEquals(2048, request[if(name == "openai") "max_completion_tokens" else "max_tokens"]!!.jsonPrimitive.int)
                val content = """{"kind":"summary","summary":"Short note"}"""
                buildJsonObject { put("choices", buildJsonArray { add(buildJsonObject { put("finish_reason", "stop"); put("message", buildJsonObject { put("content", content) }) }) }) }.toString()
            })
            assertEquals(AiSuggestion.Summary("Short note"), provider.suggest(AiRequest("summary", "Saved note")))
        }
    }
    @Test fun confirmedSelectedActionsAreAtomicAndIdempotent() = runTest {
        val source = repository.create("project", wireJson.encodeToJsonElement(Project("Garden", "Plant it")) as JsonObject)
        val proposal = AiProposal(source, AiSuggestion.Actions(listOf("Buy seeds", "Plant seeds")))
        assertTrue(db.records().all(repository.accountId).none { it.kind == "task" })
        repository.applyProposal(proposal, listOf(1))
        val task = db.records().all(repository.accountId).map { it.decode() }.single { it.kind == "task" }
        assertEquals("Plant seeds", task.data["title"]!!.jsonPrimitive.content); assertEquals(source.id, task.data["projectId"]!!.jsonPrimitive.content)
        try { repository.applyProposal(proposal, listOf(0,1)); fail("Duplicate acceptance") } catch (_: IllegalArgumentException) { }
        assertEquals(1, db.records().all(repository.accountId).count { it.kind == "task" })
    }
    @Test fun staleDeletedAndOtherAccountProposalsCannotMutateRecords() = runTest {
        val source = repository.create("task", wireJson.encodeToJsonElement(Task("Task")) as JsonObject)
        val proposal = AiProposal(source, AiSuggestion.Actions(listOf("Next")))
        repository.archive(source.id, true)
        try { repository.applyProposal(proposal, listOf(0)); fail("Changed source") } catch (_: IllegalStateException) { }
        try { NookRepository(db, "bob", "b").applyProposal(proposal, listOf(0)); fail("Other account") } catch (_: IllegalStateException) { }
        repository.delete(source.id)
        try { repository.applyProposal(proposal, listOf(0)); fail("Deleted source") } catch (_: IllegalStateException) { }
        assertEquals(1, db.records().all(repository.accountId).size)
        val target = repository.create("area", wireJson.encodeToJsonElement(Area("Health")) as JsonObject)
        val capture = repository.capture("Reference")
        repository.archive(target.id, true)
        try { repository.applyProposal(AiProposal(capture, AiSuggestion.Organize(target.id, "Reference"), destination = target)); fail("Changed destination") } catch (_: IllegalStateException) { }
        assertTrue(db.records().all(repository.accountId).none { it.kind == "note" })
    }
    @Test fun organizationPreservesOriginalsAndSummaryDoesNotOverwriteSource() = runTest {
        val target = repository.create("area", wireJson.encodeToJsonElement(Area("Health")) as JsonObject)
        val source = repository.capture("Reference", "text", listOf(OriginalInput("a.txt", "text/plain", byteArrayOf(1,2))))
        repository.applyProposal(AiProposal(source, AiSuggestion.Organize(target.id, "Reference"), destination = target))
        val note = db.records().all(repository.accountId).map { it.decode() }.single { it.kind == "note" }
        assertEquals(target.id, note.data["areaId"]!!.jsonPrimitive.content)
        assertEquals(1, db.records().originals(repository.accountId).size)
        repository.applyProposal(AiProposal(note, AiSuggestion.Summary("Short reference")))
        assertEquals(note, repository.get(note.id)); assertEquals(2, db.records().all(repository.accountId).count { it.kind == "note" })
    }
}
