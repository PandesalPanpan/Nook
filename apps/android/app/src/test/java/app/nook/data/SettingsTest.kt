package app.nook.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
class SettingsTest {
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(),NookDatabase::class.java).build()
    private val repo = NookRepository(db,"local:tips","client")
    private suspend fun settings() = currentSettings(db.records().all(repo.accountId).map { it.decode() })!!
    private fun Record.payload() = wireJson.decodeFromJsonElement(AppSettings.serializer(),data)
    @After fun close() { db.close() }
    @Test fun concurrentSettingsEditsPreserveOtherPreferencesAndAccountIsolation() = runTest {
        listOf(async { repo.updateSettings { it.copy(dismissedTips = listOf("para:area")) } },async { repo.updateSettings { it.copy(inboxReviewEnabled = true,dailyNotesEnabled = true) } }).awaitAll()
        assertEquals(1,db.records().all(repo.accountId).count { it.kind == "settings" })
        assertEquals(AppSettings(dismissedTips = listOf("para:area"),inboxReviewEnabled = true,dailyNotesEnabled = true),settings().payload())
        NookRepository(db,"bob","client").updateSettings { it.copy(tipsEnabled = false) }
        assertTrue(settings().payload().tipsEnabled)
        assertEquals(1,db.records().due(repo.accountId,Long.MAX_VALUE).size)
    }
    @Test fun restoredDismissalsAndRemoteSuppressionSurviveOtherEditsWithoutRevivingTombstones() = runTest {
        repo.updateSettings { it.copy(dismissedTips = listOf("para:inbox")) }
        val restored = NookRepository(db,"restored","client")
        restored.restoreBackup(repo.exportBackup())
        assertEquals(listOf("para:inbox"),currentSettings(db.records().all("restored").map { it.decode() })!!.payload().dismissedTips)
        val before = settings()
        repo.receive(before.copy(updatedAt = before.updatedAt + 100,data = wireJson.encodeToJsonElement(before.payload().copy(tipsEnabled = false)) as JsonObject))
        repo.updateSettings { it.copy(inboxReviewEnabled = true) }
        assertFalse(settings().payload().tipsEnabled)
        repo.delete(before.id);repo.updateSettings { it.copy(dismissedTips = listOf("para:archive")) }
        assertTrue(repo.get(before.id)!!.deleted)
        assertEquals(listOf("para:archive"),settings().payload().dismissedTips)
    }
}
