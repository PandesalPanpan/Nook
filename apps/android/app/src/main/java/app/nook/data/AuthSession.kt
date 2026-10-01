package app.nook.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class AuthIdentity(val uid: String, val email: String?)
interface AuthPort {
    fun current(): AuthIdentity?
    fun observe(listener: () -> Unit): () -> Unit
    suspend fun signIn(email: String, password: String)
    suspend fun register(email: String, password: String)
    suspend fun google(idToken: String) { error("Google sign-in is unavailable") }
    suspend fun signOut()
}

/** Auth credentials never enter Room, settings records, outboxes or backups. */
class AuthSession(val accounts: AccountSession, private val auth: AuthPort, private val scope: CoroutineScope, private val beforeCredentialChange: suspend () -> Unit = {}) {
    private val operations = Mutex()
    private var initialized = false
    private var closed = false
    private var unsubscribe: (() -> Unit)? = null
    fun identity(): AuthIdentity? = auth.current()
    suspend fun initialize() = operations.withLock { initializeLocked() }
    private suspend fun initializeLocked() {
        check(!closed) { "Authentication session is closed" }
        if (initialized) return
        accounts.activate(auth.current()?.uid)
        unsubscribe = auth.observe {
            scope.launch {
                operations.withLock {
                    if (!closed) {
                        try {
                            val identity = auth.current()?.uid
                            val account = accounts.state.value.repository.accountId
                            // Firebase's initial callback may describe the already-restored identity.
                            if (identity != account && !(identity == null && account.startsWith("local:"))) {
                                accounts.prepareSignOut()
                                beforeCredentialChange()
                                accounts.activate(identity)
                            }
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { /* AccountSession exposes transition failures in state. */ }
                    }
                }
            }
        }
        initialized = true
    }
    suspend fun signIn(email: String, password: String, createAccount: Boolean = false) = operations.withLock {
        initializeLocked()
        changeCredentials { if (createAccount) auth.register(email.trim(), password) else auth.signIn(email.trim(), password) }
    }
    suspend fun signInWithGoogle(loadToken: suspend () -> String) = operations.withLock {
        initializeLocked()
        // A dismissed account picker must not stop the current account's sync engine.
        val token = loadToken()
        require(token.isNotBlank()) { "Google did not return a sign-in token" }
        changeCredentials { auth.google(token) }
    }
    private suspend fun changeCredentials(change: suspend () -> Unit) {
        accounts.prepareSignOut()
        try {
            beforeCredentialChange()
            change()
            val identity = requireNotNull(auth.current()) { "Sign-in did not establish an account" }
            accounts.activate(identity.uid, mergeGuest = true)
        } finally {
            // Reconcile even if the caller disappears after Firebase changes credentials.
            withContext(NonCancellable) { accounts.activate(auth.current()?.uid) }
        }
    }
    suspend fun signOut() = operations.withLock {
        initializeLocked(); accounts.prepareSignOut()
        try { beforeCredentialChange(); auth.signOut() }
        finally { withContext(NonCancellable) { accounts.activate(auth.current()?.uid) } }
    }
    suspend fun dispose() = operations.withLock {
        closed = true; unsubscribe?.invoke(); unsubscribe = null
        accounts.prepareSignOut()
    }
}
