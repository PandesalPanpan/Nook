package app.nook

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.nook.data.*
import app.nook.integration.outgoingNotes
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class NoteLinkPersistenceTest {
    @Test fun titleLinksPersistTheirTargetAcrossRenameOutboxAndReopen() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "links-${java.util.UUID.randomUUID()}.db"
        var db = Room.databaseBuilder(context, NookDatabase::class.java, name).build()
        try {
            var repo = NookRepository(db, "local:links", "client")
            val target = repo.create("note", wireJson.encodeToJsonElement(Note("Garden", "")) as JsonObject)
            val source = repo.create("note", wireJson.encodeToJsonElement(Note("Source", "[[Garden]] and `[[Garden]]`")) as JsonObject)
            val expected = "[[${target.id}|Garden]] and `[[Garden]]`"
            assertEquals(expected, source.data["body"]!!.jsonPrimitive.content)
            repo.update(target.id) { it.copy(data = JsonObject(it.data + ("title" to JsonPrimitive("Renamed")))) }
            val queued = wireJson.decodeFromString<Record>(db.records().operation(repo.accountId, source.id)!!.json)
            assertEquals(expected, queued.data["body"]!!.jsonPrimitive.content)
            db.close()
            db = Room.databaseBuilder(context, NookDatabase::class.java, name).build()
            repo = NookRepository(db, "local:links", "client")
            val notes = db.records().byKinds(repo.accountId, listOf("note")).map { it.decode() }
            assertEquals(setOf(target.id), outgoingNotes(repo.get(source.id)!!.data["body"]!!.jsonPrimitive.content, notes))
            val foreign = NookRepository(db, "foreign", "client")
            foreign.create("note", wireJson.encodeToJsonElement(Note("Foreign", "")) as JsonObject)
            val updated = repo.update(source.id) { it.copy(data = JsonObject(it.data + ("body" to JsonPrimitive("[[Renamed]] [[Foreign]]")))) }
            assertEquals("[[${target.id}|Renamed]] [[Foreign]]", updated.data["body"]!!.jsonPrimitive.content)
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
