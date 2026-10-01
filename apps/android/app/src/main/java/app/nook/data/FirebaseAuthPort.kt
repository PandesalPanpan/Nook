package app.nook.data

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.tasks.await

class FirebaseAuthPort(private val auth: FirebaseAuth, private val signedOut: SignedOutFence? = null, private val clearCredentials: suspend () -> Unit = {}) : AuthPort {
    init { if(signedOut?.isSignedOut()==true) auth.signOut() }
    override fun current(): AuthIdentity? = if(signedOut?.isSignedOut()==true) null else auth.currentUser?.let { AuthIdentity(it.uid, it.email) }
    override fun observe(listener: () -> Unit): () -> Unit {
        val observer = FirebaseAuth.AuthStateListener { listener() }
        auth.addAuthStateListener(observer)
        return { auth.removeAuthStateListener(observer) }
    }
    override suspend fun signIn(email: String, password: String) { auth.signInWithEmailAndPassword(email, password).await(); allowRestoration() }
    override suspend fun register(email: String, password: String) { auth.createUserWithEmailAndPassword(email, password).await(); allowRestoration() }
    override suspend fun google(idToken: String) { auth.signInWithCredential(GoogleAuthProvider.getCredential(idToken, null)).await(); allowRestoration() }
    private suspend fun allowRestoration() = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { signedOut?.signedIn(); Unit }
    override suspend fun signOut() {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { signedOut?.signOut() }
        auth.signOut(); clearCredentials()
    }
}
