package app.nook.data

import android.content.Context
import android.annotation.SuppressLint
import android.content.SharedPreferences
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/** Local operational metadata, separate from exported or synchronized user settings. */
class SyncCheckHistory(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("nook-sync-checks", Context.MODE_PRIVATE)

    fun lastCheckedAt(accountId: String): Long? = preferences.getLong(accountId, 0).takeIf { it > 0 }

    // Worker success requires durable storage and the commit result; the KTX edit helper returns Unit.
    @SuppressLint("ApplySharedPref", "UseKtx")
    fun recordSuccess(accountId: String, checkedAt: Long): Boolean = synchronized(lock) {
        require(!accountId.startsWith("local:") && accountId.isNotBlank() && checkedAt > 0)
        if ((lastCheckedAt(accountId) ?: 0) >= checkedAt) true
        else preferences.edit().putLong(accountId, checkedAt).commit()
    }

    fun observe(accountId: String): Flow<Long?> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == accountId || key == null) trySend(lastCheckedAt(accountId))
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        trySend(lastCheckedAt(accountId))
        awaitClose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }.distinctUntilChanged()

    private companion object { val lock = Any() }
}
