package app.nook.data

import android.content.Context
import com.google.firebase.FirebaseOptions
import kotlinx.serialization.Serializable

@Serializable
data class FirebaseConfiguration(
    val apiKey: String,
    val applicationId: String,
    val projectId: String,
    val storageBucket: String? = null,
    val emulatorHost: String? = null,
    val googleWebClientId: String? = null
) {
    fun validateConfiguration(): FirebaseConfiguration {
        require(apiKey.isNotBlank() && applicationId.isNotBlank() && projectId.isNotBlank()) { "Firebase configuration requires API key, application ID and project ID" }
        require(!projectId.startsWith("demo-") || !emulatorHost.isNullOrBlank()) { "Demo Firebase projects require an emulator host" }
        require(emulatorHost == null || emulatorHost.isNotBlank()) { "Emulator host cannot be blank" }
        require(googleWebClientId == null || googleWebClientId.isNotBlank()) { "Google web client ID cannot be blank" }
        return this
    }
    fun options(): FirebaseOptions {
        validateConfiguration()
        return FirebaseOptions.Builder().setApiKey(apiKey).setApplicationId(applicationId).setProjectId(projectId)
            .apply { storageBucket?.let { setStorageBucket(it) } }.build()
    }
}

/** Missing public configuration selects local-only mode; malformed configuration is an error. */
fun loadFirebaseConfiguration(context: Context): FirebaseConfiguration? {
    return loadFirebaseConfigurationFromAsset(context, "nook-firebase.json")
}

internal fun loadFirebaseConfigurationFromAsset(context: Context, filename: String): FirebaseConfiguration? {
    if (context.assets.list("")?.contains(filename) != true) return null
    return context.assets.open(filename).bufferedReader().use { reader ->
        parseFirebaseConfiguration(reader.readText())
    }
}

internal fun parseFirebaseConfiguration(raw: String): FirebaseConfiguration =
    wireJson.decodeFromString<FirebaseConfiguration>(raw).validateConfiguration()
