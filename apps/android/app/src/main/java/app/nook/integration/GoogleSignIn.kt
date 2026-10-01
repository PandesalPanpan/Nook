package app.nook.integration

import android.content.Context
import android.util.Base64
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import java.security.SecureRandom
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object GoogleSignIn {
    suspend fun token(context: Context, webClientId: String): String = withContext(Dispatchers.Main.immediate) {
        val nonce = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val option = GetSignInWithGoogleOption.Builder(webClientId)
            .setNonce(Base64.encodeToString(nonce, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)).build()
        val result = try {
            CredentialManager.create(context).getCredential(context, GetCredentialRequest.Builder().addCredentialOption(option).build())
        } catch (cancelled: GetCredentialCancellationException) {
            throw CancellationException("Google sign-in cancelled", cancelled)
        } catch (missing: NoCredentialException) {
            throw IllegalStateException("No Google account is available. Add an account or use email.", missing)
        }
        val credential = result.credential
        require(credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) { "Google did not return a supported credential" }
        GoogleIdTokenCredential.createFrom(credential.data).idToken
    }
    suspend fun clear(context: Context) { CredentialManager.create(context).clearCredentialState(ClearCredentialStateRequest()) }
}
