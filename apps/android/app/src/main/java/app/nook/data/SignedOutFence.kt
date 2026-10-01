package app.nook.data

import android.content.SharedPreferences

/** App-owned durable user intent; contains no credentials, identifiers of users, or cloud data. */
class SignedOutFence(private val preferences: SharedPreferences, private val scope: String) {
    private val key="signed-out:$scope"
    fun isSignedOut(): Boolean = preferences.getBoolean(key,false)
    fun signOut() { check(preferences.edit().putBoolean(key,true).commit()) { "Could not preserve sign-out" } }
    fun signedIn() { check(preferences.edit().remove(key).commit()) { "Could not preserve sign-in" } }
}
