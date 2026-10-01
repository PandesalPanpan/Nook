package app.nook.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import androidx.room.withTransaction

interface SyncTransport {
    /** Atomically compare versions and return the retained cloud version. */
    suspend fun push(record: Record): Record
    suspend fun pull(accountId: String): List<Record>
    val originals: OriginalTransport? get() = null
}
interface OriginalTransport {
    val cacheKey: String? get() = null
    suspend fun upload(record: Record, bytes: ByteArray)
    suspend fun uploadWithProgress(record: Record, bytes: ByteArray, progress: (Long) -> Unit) {
        upload(record, bytes)
        progress(bytes.size.toLong())
    }
    suspend fun download(record: Record): ByteArray
    suspend fun remove(record: Record)
}
class SyncEngine(private val repository: NookRepository, private val transport: SyncTransport, private val clock: () -> Long = System::currentTimeMillis) {
    private val mutex = Mutex()
    @Volatile private var active = true
    private val calls = java.util.concurrent.ConcurrentHashMap.newKeySet<Job>()
    private var progressObserver: ((FileSyncProgress) -> Unit)? = null
    fun observeProgress(observer: (FileSyncProgress) -> Unit): SyncEngine { progressObserver = observer; return this }
    suspend fun stop() {
        active = false
        calls.toList().forEach { it.cancel() }
        mutex.withLock { }
        progressObserver = null
    }
    suspend fun sync(): Unit = coroutineScope {
        val call = async(start = CoroutineStart.LAZY) { syncActive() }
        calls.add(call)
        try {
            if (!active) call.cancel() else call.start()
            call.await()
        } catch (cancelled: CancellationException) {
            currentCoroutineContext().ensureActive()
            if (active) throw cancelled
        } finally { calls.remove(call) }
    }
    private suspend fun syncActive() = mutex.withLock {
        if (!active || repository.accountId.startsWith("local:")) return@withLock
        val dao = repository.db.records()
        for (operation in dao.due(repository.accountId, clock())) {
            if (!active) return@withLock
            val sent = wireJson.decodeFromString<Record>(operation.json)
            try {
                val winner = transport.push(sent)
                if (!active) return@withLock
                repository.receive(winner)
                repository.db.withTransaction {
                    val current = dao.operation(operation.accountId, operation.entityId)
                    if (current != null && compareVersions(wireJson.decodeFromString(current.json), sent) == 0) {
                        dao.acknowledge(operation.accountId, operation.entityId)
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (!active) return@withLock
                repository.db.withTransaction {
                    val current = dao.operation(operation.accountId, operation.entityId)
                    if (current != null && compareVersions(wireJson.decodeFromString(current.json), sent) == 0) {
                        val attempts = current.attempts + 1
                        val delay = minOf(300_000L, 1000L * (1L shl minOf(attempts - 1, 9)))
                        dao.enqueue(current.copy(attempts = attempts, nextAttemptAt = clock() + delay))
                    }
                }
            }
        }
        if (!active) return@withLock
        val remoteRecords = transport.pull(repository.accountId)
        for (remote in remoteRecords) {
            if (!active) return@withLock
            repository.receive(remote)
        }
        transport.originals?.let { originals ->
            var failure: Exception? = null
            val cloud = remoteRecords.associateBy { it.id }
            // Records survive metadata acknowledgement and process death: every missing
            // original and retained tombstone remains eligible for the next retry.
            val attachments = dao.byKinds(repository.accountId, listOf("attachment")).map { it.decode() }.filter { record ->
                val retained = cloud[record.id]
                val attachment = wireJson.decodeFromJsonElement(Attachment.serializer(), record.data)
                val parent = cloud[attachment.ownerId]
                retained != null && compareVersions(retained, record) == 0 && (record.deleted || parent != null && !parent.deleted)
            }
            var progress = FileSyncProgress(attachments.size)
            fun publish(value: FileSyncProgress) { if(active) progressObserver?.invoke(value) }
            publish(progress)
            for (record in attachments) {
                if (!active) return@withLock
                val attachment = wireJson.decodeFromJsonElement(Attachment.serializer(), record.data)
                progress = progress.copy(phase = FileSyncPhase.CHECKING, filename = attachment.filename)
                publish(progress)
                val fileActive = java.util.concurrent.atomic.AtomicBoolean(true)
                var completedCheck = true
                try {
                    val scope = originals.cacheKey
                    val version = wireJson.encodeToString(record)
                    val revision = if (scope != null) dao.originalRevision(repository.accountId, record.id).orEmpty() else ""
                    var acknowledgedRevision = revision
                    val previous = if (scope != null) dao.transfer(repository.accountId, record.id) else null
                    val age = previous?.let { clock() - it.checkedAt } ?: -1
                    if (scope != null && previous != null && previous.scope == scope && previous.version == version && previous.revision == revision && age in 0 until 15 * 60 * 1000 && (record.deleted || revision.isNotEmpty())) continue
                    val original = dao.original(repository.accountId, record.id)
                    if (record.deleted) {
                        progress = progress.copy(phase = FileSyncPhase.REMOVING); publish(progress)
                        originals.remove(record)
                    } else if (original != null) {
                        progress = progress.copy(phase = FileSyncPhase.UPLOADING, transferred = 0, size = attachment.size); publish(progress)
                        val base = progress
                        val lastPercent = java.util.concurrent.atomic.AtomicInteger(0)
                        originals.uploadWithProgress(record, original.bytes) { transferred ->
                            val bytes = transferred.coerceIn(0, attachment.size)
                            val percent = if(attachment.size > 0) (bytes * 100 / attachment.size).toInt() else 100
                            // At most twenty byte-progress updates per file, fenced against late SDK callbacks.
                            val previous = lastPercent.get()
                            if(fileActive.get() && percent >= previous + 5 && lastPercent.compareAndSet(previous, percent)) publish(base.copy(transferred = bytes))
                        }
                    }
                    else {
                        progress = progress.copy(phase = FileSyncPhase.DOWNLOADING, size = attachment.size); publish(progress)
                        val bytes = originals.download(record)
                        if (!active) return@withLock
                        require(bytes.size.toLong() == attachment.size) { "Attachment size mismatch" }
                        repository.db.withTransaction {
                            val current = repository.get(record.id)
                            if (current != null && !current.deleted && compareVersions(current, record) == 0 && dao.originalRevision(repository.accountId, record.id) == null) {
                                dao.putOriginal(OriginalFile(repository.accountId, record.id, bytes))
                                if (scope != null) acknowledgedRevision = dao.originalRevision(repository.accountId, record.id).orEmpty()
                            }
                        }
                    }
                    if (scope != null && active) repository.db.withTransaction {
                        val current = dao.get(repository.accountId, record.id)
                        val currentRevision = dao.originalRevision(repository.accountId, record.id).orEmpty()
                        if (current?.json == version && (record.deleted || (currentRevision.isNotEmpty() && currentRevision == acknowledgedRevision)))
                            dao.acknowledgeTransfer(OriginalTransfer(repository.accountId, record.id, scope, version, currentRevision, clock()))
                    }
                } catch (cancelled: CancellationException) { completedCheck = false; throw cancelled }
                catch (error: Exception) { failure = error; progress = progress.copy(failed = progress.failed + 1) }
                finally {
                    fileActive.set(false)
                    progress = FileSyncProgress(progress.total, progress.checked + if(completedCheck) 1 else 0, progress.failed)
                    publish(progress)
                }
            }
            failure?.let { throw it }
        }
    }
}
