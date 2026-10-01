package app.nook.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class AccountState(val repository: NookRepository, val changing: Boolean = false, val error: String? = null,
    val syncing: Boolean = false, val syncError: String? = null, val lastSyncAt: Long? = null, val syncErrorAt: Long? = null,
    val files: FileSyncProgress? = null)

/** Namespace changes serialize with worker shutdown; UI keys editors to accountId. */
class AccountSession(private val guest: NookRepository, private val activated: suspend (NookRepository) -> Unit = {},
    private val paused: suspend () -> Unit = {}, private val resumed: suspend (NookRepository) -> Unit = {},
    private val transport: (String) -> SyncTransport) {
    init { require(guest.accountId.startsWith("local:")) }
    private val transitions = Mutex()
    private val mutableState = MutableStateFlow(AccountState(guest))
    val state: StateFlow<AccountState> = mutableState.asStateFlow()
    private var engine: SyncEngine? = null
    private class SyncGroup(val engine: SyncEngine, var calls: Int = 0)
    @Volatile private var syncGroup: SyncGroup? = null
    suspend fun activate(accountId: String?, mergeGuest: Boolean = false) = transitions.withLock {
        require(accountId == null || (accountId.isNotBlank() && !accountId.startsWith("local:")))
        val previous = mutableState.value.repository
        val target = accountId ?: guest.accountId
        if (target == previous.accountId && (accountId == null || engine != null)) return@withLock
        mutableState.value = AccountState(previous, changing = true)
        try {
            paused()
            engine?.stop(); engine = null; syncGroup = null
            val adapter = accountId?.let(transport)
            val next = when {
                accountId == null -> guest
                mergeGuest && previous.accountId == guest.accountId -> guest.mergeIntoAccount(accountId)
                else -> NookRepository(guest.db, accountId, guest.clientId)
            }
            activated(next)
            engine = adapter?.let { SyncEngine(next, it).observeProgress { files ->
                mutableState.update { state -> if(state.repository.accountId == next.accountId && !state.changing) state.copy(files = files) else state }
            } }
            syncGroup = engine?.let { SyncGroup(it) }
            mutableState.value = AccountState(next)
            resumed(next)
        } catch (cancelled: CancellationException) {
            mutableState.value = AccountState(previous)
            throw cancelled
        } catch (error: Exception) {
            mutableState.value = AccountState(previous, error = error.message ?: "Could not switch accounts")
            throw error
        }
    }
    suspend fun prepareSignOut() = transitions.withLock { paused(); engine?.stop(); engine = null; syncGroup = null
        mutableState.update { it.copy(syncing = false, syncError = null, lastSyncAt = null, syncErrorAt = null, files = null) }
    }
    suspend fun sync() {
        val group = transitions.withLock {
            val current = syncGroup ?: return
            synchronized(current) {
                current.calls++
                mutableState.update { it.copy(syncing = true, syncError = null, syncErrorAt = null, files = if(it.syncing) it.files else null) }
            }
            current
        }
        var successful = false
        var failure: String? = null
        try { group.engine.sync(); successful = true }
        catch(cancelled: CancellationException) { throw cancelled }
        catch(error: Exception) {
            failure = "Some changes or files could not sync. Your saved data stays on this device; Nook will retry."
            throw error
        } finally {
            // Keep counter and publication together; a newly started call cannot be hidden by an older completion.
            synchronized(group) {
                group.calls--
                // Non-suspending completion avoids taking the transition lock during worker shutdown.
                mutableState.update { state ->
                    if(syncGroup !== group || state.changing) state
                    else state.copy(syncing = group.calls > 0, syncError = failure ?: if(successful) null else state.syncError,
                        lastSyncAt = if(successful) System.currentTimeMillis() else state.lastSyncAt,
                        syncErrorAt = if(failure != null) System.currentTimeMillis() else if(successful) null else state.syncErrorAt)
                }
            }
        }
    }
}
