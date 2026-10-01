package app.nook.data

import android.content.SharedPreferences
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Stable device identity and the active namespace shared by every Android surface. */
class AccountPreferences(private val local: SharedPreferences, private val session: SharedPreferences) {
    private fun identity(key: String, prefix: String = ""): String = synchronized(identityLock) {
        local.getString(key, null) ?: (prefix + UUID.randomUUID()).also {
            check(local.edit().putString(key, it).commit()) { "Could not preserve device identity" }
        }
    }
    val clientId = identity("client")
    val guestAccountId = identity("guest", "local:")
    private fun current() = session.getString("accountId", null) ?: guestAccountId
    fun activeAccountId(): String = current()
    private val mutableAccount = MutableStateFlow(current())
    val accountId: StateFlow<String> = mutableAccount.asStateFlow()
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null || key == "accountId") mutableAccount.value = current()
    }
    init { session.registerOnSharedPreferenceChangeListener(listener) }
    fun activate(accountId: String?) {
        require(accountId == null || accountId.isNotBlank())
        val editor = session.edit()
        if (accountId == null || accountId == guestAccountId) editor.remove("accountId") else editor.putString("accountId", accountId)
        check(editor.commit()) { "Could not preserve active account" }
        mutableAccount.value = current()
    }
    fun close() { session.unregisterOnSharedPreferenceChangeListener(listener) }
    companion object { private val identityLock = Any() }
}
