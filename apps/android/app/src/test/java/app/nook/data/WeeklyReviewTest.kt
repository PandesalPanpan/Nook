package app.nook.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
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
class WeeklyReviewTest {
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), NookDatabase::class.java).build()
    private val repo = NookRepository(db, "local:review", "android", { 100L })
    @After fun close() = db.close()
    @Test fun focusUsesMondayIdentityAndUpdatesWithoutDuplicatesOrResurrection() = runTest {
        assertEquals("2026-12-28", weekStart("2027-01-01"))
        val first = repo.saveWeeklyFocus("2026-09-30", "Finish prototype")
        val second = repo.saveWeeklyFocus("2026-10-04", "Prepare demo")
        assertEquals(first.id, second.id);assertEquals("Prepare demo", wireJson.decodeFromJsonElement(Note.serializer(), second.data).body)
        assertEquals(1, db.records().all(repo.accountId).count { it.kind == "note" })
        repo.delete(first.id)
        assertEquals("weekly-focus-2026-09-28-2", repo.saveWeeklyFocus("2026-09-30", "New focus").id)
        repo.receive(first);assertTrue(repo.get(first.id)!!.deleted)
        val other = NookRepository(db, "local:other", "other")
        assertEquals(first.id, other.saveWeeklyFocus("2026-09-30", "Other focus").id)
    }
}
