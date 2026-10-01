package app.nook.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.RoomDatabase
import androidx.room.Fts4
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "records", primaryKeys = ["accountId", "id"], indices = [Index(value = ["accountId", "kind", "deleted"])])
data class StoredRecord(val accountId: String, val id: String, val kind: String, val deleted: Boolean, val updatedAt: Long, val json: String)
@Entity(tableName = "outbox", primaryKeys = ["accountId", "entityId"], indices = [Index(value = ["accountId", "nextAttemptAt"])])
data class OutboxEntry(val accountId: String, val entityId: String, val json: String, val attempts: Int = 0, val nextAttemptAt: Long = 0)
@Fts4
@Entity(tableName = "search")
data class SearchRow(val accountId: String, val recordId: String, val text: String)
data class OriginalFile(val accountId: String, val id: String, val bytes: ByteArray)
@Entity(tableName = "originals", primaryKeys = ["accountId", "id", "part"])
data class OriginalChunk(val accountId: String, val id: String, val part: Int, val bytes: ByteArray, @androidx.room.ColumnInfo(defaultValue = "''") val revision: String = "")
@Entity(tableName = "original_transfers", primaryKeys = ["accountId", "id"])
data class OriginalTransfer(val accountId: String, val id: String, val scope: String, val version: String, val revision: String, val checkedAt: Long)

@Dao
interface NookDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putChunks(chunks: List<OriginalChunk>)
    @Query("SELECT * FROM originals WHERE accountId = :account AND id = :id ORDER BY part") suspend fun originalChunks(account: String, id: String): List<OriginalChunk>
    @Query("SELECT DISTINCT id FROM originals WHERE accountId = :account") suspend fun originalIds(account: String): List<String>
    @Query("SELECT revision FROM originals WHERE accountId = :account AND id = :id AND part = 0") suspend fun originalRevision(account: String, id: String): String?
    @Query("SELECT * FROM original_transfers WHERE accountId = :account AND id = :id") suspend fun transfer(account: String, id: String): OriginalTransfer?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun acknowledgeTransfer(transfer: OriginalTransfer)
    @Transaction
    suspend fun putOriginal(file: OriginalFile) {
        removeOriginal(file.accountId, file.id)
        val revision = java.util.UUID.randomUUID().toString()
        val parts = if(file.bytes.isEmpty()) listOf(OriginalChunk(file.accountId, file.id, 0, byteArrayOf(), revision)) else
            (file.bytes.indices step 256 * 1024).mapIndexed { index, start -> OriginalChunk(file.accountId, file.id, index, file.bytes.copyOfRange(start, minOf(start + 256 * 1024, file.bytes.size)), revision) }
        putChunks(parts)
    }
    suspend fun original(account: String, id: String): OriginalFile? {
        val chunks = originalChunks(account, id)
        if(chunks.isEmpty()) return null
        val bytes = ByteArray(chunks.sumOf { it.bytes.size }); var offset = 0
        chunks.forEach { it.bytes.copyInto(bytes, offset); offset += it.bytes.size }
        return OriginalFile(account, id, bytes)
    }
    suspend fun originals(account: String): List<OriginalFile> = originalIds(account).mapNotNull { original(account, it) }
    @Query("DELETE FROM originals WHERE accountId = :account AND id = :id") suspend fun removeOriginal(account: String, id: String)
    @Query("SELECT * FROM records WHERE accountId = :account AND deleted = 0 ORDER BY updatedAt DESC")
    fun observeAll(account: String): Flow<List<StoredRecord>>
    @Query("SELECT * FROM records WHERE accountId = :account AND kind IN (:kinds) ORDER BY updatedAt DESC")
    suspend fun byKinds(account: String, kinds: List<String>): List<StoredRecord>
    @Insert suspend fun index(row: SearchRow)
    @Query("DELETE FROM search WHERE accountId = :account AND recordId = :id") suspend fun unindex(account: String, id: String)
    @Query("SELECT * FROM records WHERE accountId = :account AND deleted = 0 AND id IN (SELECT recordId FROM search WHERE accountId = :account AND text MATCH :expression)")
    suspend fun search(account: String, expression: String): List<StoredRecord>
    @Query("SELECT * FROM records WHERE accountId = :account AND deleted = 0 AND id IN (SELECT recordId FROM search WHERE accountId = :account)")
    suspend fun searchableRecords(account: String): List<StoredRecord>
    @Query("SELECT * FROM records WHERE accountId = :account AND kind = :kind AND deleted = 0 ORDER BY updatedAt DESC")
    fun observe(account: String, kind: String): Flow<List<StoredRecord>>
    @Query("SELECT * FROM records WHERE accountId = :account") suspend fun all(account: String): List<StoredRecord>
    @Query("SELECT * FROM records WHERE accountId = :account AND id = :id") suspend fun get(account: String, id: String): StoredRecord?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(record: StoredRecord)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun enqueue(operation: OutboxEntry)
    @Query("SELECT * FROM outbox WHERE accountId = :account AND nextAttemptAt <= :now") suspend fun due(account: String, now: Long): List<OutboxEntry>
    @Query("SELECT * FROM outbox WHERE accountId = :account ORDER BY entityId") fun observeOutbox(account: String): Flow<List<OutboxEntry>>
    @Query("SELECT * FROM outbox WHERE accountId = :account AND entityId = :id") suspend fun operation(account: String, id: String): OutboxEntry?
    @Query("DELETE FROM outbox WHERE accountId = :account AND entityId = :id") suspend fun acknowledge(account: String, id: String)
    @Query("DELETE FROM records WHERE accountId = :account AND id = :id") suspend fun removeForMigration(account: String, id: String)
}
@Database(entities = [StoredRecord::class, OutboxEntry::class, SearchRow::class, OriginalChunk::class, OriginalTransfer::class], version = 4, exportSchema = true)
abstract class NookDatabase : RoomDatabase() { abstract fun records(): NookDao }

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE originals ADD COLUMN revision TEXT NOT NULL DEFAULT ''")
        db.execSQL("UPDATE originals SET revision = lower(hex(randomblob(16))) WHERE part = 0")
        db.execSQL("CREATE TABLE IF NOT EXISTS original_transfers (accountId TEXT NOT NULL, id TEXT NOT NULL, scope TEXT NOT NULL, version TEXT NOT NULL, revision TEXT NOT NULL, checkedAt INTEGER NOT NULL, PRIMARY KEY(accountId, id))")
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS originals (accountId TEXT NOT NULL, id TEXT NOT NULL, part INTEGER NOT NULL, bytes BLOB NOT NULL, PRIMARY KEY(accountId, id, part))")
    }
}

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS `search` USING FTS4(`accountId` TEXT NOT NULL, `recordId` TEXT NOT NULL, `text` TEXT NOT NULL)")
        db.query("SELECT json FROM records").use { cursor ->
            while (cursor.moveToNext()) {
                val record = wireJson.decodeFromString<Record>(cursor.getString(0))
                searchable(record)?.let { text -> db.execSQL("INSERT INTO search(accountId, recordId, text) VALUES (?, ?, ?)", arrayOf(record.accountId, record.id, text)) }
            }
        }
    }
}

fun StoredRecord.decode(): Record = wireJson.decodeFromString(json)
fun Record.store(): StoredRecord = StoredRecord(accountId, id, kind, deleted, updatedAt, wireJson.encodeToString(this))
