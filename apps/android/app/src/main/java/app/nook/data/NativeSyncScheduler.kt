package app.nook.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Watches committed payloads, not retry counters. WorkManager owns durable execution/reconnect. */
@OptIn(FlowPreview::class)
class NativeSyncScheduler(
    private val scope: CoroutineScope,
    private val enqueue: suspend (String) -> Unit,
    private val periodic: suspend (String) -> Unit,
    private val debounceMillis: Long = 250,
    private val retryMillis: Long = 30_000,
    private val foreground: Flow<Boolean> = flowOf(false),
    private val foregroundIntervalMillis: Long = 30_000
) {
    private val transitions = Mutex()
    private var observer: Job? = null
    suspend fun start(repository: NookRepository) = transitions.withLock {
        observer?.cancelAndJoin(); observer = null
        if (repository.accountId.startsWith("local:")) return@withLock
        observer = scope.launch {
            while (isActive) {
                try {
                    coroutineScope {
                        periodic(repository.accountId)
                        val signals = Channel<Unit>(Channel.CONFLATED)
                        launch {
                            var first = true
                            repository.db.records().observeOutbox(repository.accountId)
                                .map { operations -> operations.map { it.entityId to it.json } }
                                .distinctUntilChanged().debounce(debounceMillis)
                                .collect { payloads ->
                                    // Initial empty accounts still pull; acknowledgements do not queue more pulls.
                                    if (first || payloads.isNotEmpty()) signals.send(Unit)
                                    first = false
                                }
                        }
                        launch {
                            foreground.distinctUntilChanged().collectLatest { visible ->
                                if (visible) while (isActive) { signals.send(Unit); delay(foregroundIntervalMillis) }
                            }
                        }
                        for (signal in signals) enqueue(repository.accountId)
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { delay(retryMillis) }
            }
        }
    }
    /** Join before the credential barrier, so no old-account enqueue can race its cancellation. */
    suspend fun stop() = transitions.withLock { observer?.cancelAndJoin(); observer = null }
}
