package app.nook.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class AccountActionState(val busy: Boolean = false, val error: String? = null, val message: String? = null)

/** Process-owned actions survive the account-keyed UI remount. No credentials are retained in state. */
class AccountActions(val auth: AuthSession, private val scope: CoroutineScope) {
    private val mutableState = MutableStateFlow(AccountActionState())
    val state = mutableState.asStateFlow()
    fun signIn(email: String, password: String, register: Boolean): Job? = run("Sync enabled · saved locally") { auth.signIn(email, password, register) }
    fun google(loadToken: suspend () -> String): Job? = run("Sync enabled · saved locally") { auth.signInWithGoogle(loadToken) }
    fun signOut(): Job? = run("Disconnected · local-only mode") { auth.signOut() }
    fun sync(): Job? = run("Sync checked. Queued changes stay safe on this device.") {
        try { auth.accounts.sync() }
        catch(cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch(error: Exception) { throw IllegalStateException("Sync could not finish. Nook will retry.") }
    }
    private fun run(message: String, work: suspend () -> Unit): Job? {
        if (!mutableState.compareAndSet(mutableState.value.takeUnless { it.busy } ?: return null, AccountActionState(busy = true))) return null
        return scope.launch {
            try { work(); mutableState.value = AccountActionState(message = message) }
            catch (cancelled: CancellationException) { mutableState.value = AccountActionState(); throw cancelled }
            catch (error: Exception) { mutableState.value = AccountActionState(error = error.message ?: "Could not change account. Please try again.") }
        }
    }
}
