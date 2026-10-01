package app.nook

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import app.nook.data.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.system.measureNanoTime

class SearchPerformanceTest {
    @Test fun contextualIndexedSearchOverTwoThousandTasksReturnsTheExpectedResults() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), NookDatabase::class.java).build()
        val repo = NookRepository(db, "local:search-performance", "client")
        try {
            db.withTransaction {
                val area = repo.create("area", wireJson.encodeToJsonElement(Area("BenchmarkArea")) as JsonObject)
                val project = repo.create("project", wireJson.encodeToJsonElement(Project("BenchmarkProject", areaId = area.id)) as JsonObject)
                repeat(2000) { i -> repo.create("task", wireJson.encodeToJsonElement(Task("Needle task $i", projectId = project.id)) as JsonObject) }
            }
            val query = "project:BenchmarkProject area:BenchmarkArea needle"
            assertEquals(100, repo.search(query).size)
            val samples = List(5) { measureNanoTime { assertEquals(100, repo.search(query).size) } / 1_000_000.0 }.sorted()
            println("NOOK_SEARCH_PERFORMANCE records=2002 warmMedianMs=${samples[2]} samplesMs=$samples")
            var rows = emptyList<StoredRecord>()
            val queryMs = measureNanoTime { rows = db.records().search(repo.accountId, searchExpression("needle")) } / 1_000_000.0
            var parents = emptyList<StoredRecord>()
            val parentMs = measureNanoTime { parents = db.records().all(repo.accountId) } / 1_000_000.0
            val decodeMs = measureNanoTime { (rows + parents).forEach { it.decode() } } / 1_000_000.0
            println("NOOK_SEARCH_STAGES queryMs=$queryMs parentMs=$parentMs decodeMs=$decodeMs")
            db.openHelper.readableDatabase.query("EXPLAIN QUERY PLAN SELECT * FROM records WHERE accountId = ? AND deleted = 0 AND id IN (SELECT recordId FROM search WHERE accountId = ? AND text MATCH ?)", arrayOf(repo.accountId, repo.accountId, searchExpression("needle"))).use { cursor ->
                while(cursor.moveToNext()) println("NOOK_SEARCH_PLAN ${cursor.getString(3)}")
            }
        } finally { db.close() }
    }
}
