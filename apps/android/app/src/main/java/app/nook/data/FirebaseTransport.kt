package app.nook.data

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageException
import com.google.firebase.storage.StorageMetadata
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.json.*
import org.json.JSONObject

data class FirebaseServices(val app: FirebaseApp, val auth: FirebaseAuth, val firestore: FirebaseFirestore, val storage: FirebaseStorage)

fun initializeNookFirebase(context: Context, options: FirebaseOptions, emulatorHost: String? = null): FirebaseServices {
    val app = FirebaseApp.initializeApp(context, options, "nook")
    val auth = FirebaseAuth.getInstance(app)
    val firestore = FirebaseFirestore.getInstance(app)
    val storage = FirebaseStorage.getInstance(app)
    if (emulatorHost != null) {
        auth.useEmulator(emulatorHost, 9099)
        firestore.useEmulator(emulatorHost, 8080)
        storage.useEmulator(emulatorHost, 9199)
    }
    return FirebaseServices(app, auth, firestore, storage)
}

private fun JsonElement.firestoreValue(): Any? = when (this) {
    JsonNull -> null
    is JsonObject -> mapValues { (_, value) -> value.firestoreValue() }
    is JsonArray -> map { it.firestoreValue() }
    is JsonPrimitive -> if (isString) content else booleanOrNull ?: longOrNull ?: doubleOrNull ?: content
}

class FirebaseTransport(private val firestore: FirebaseFirestore, private val accountId: String, storage: FirebaseStorage = FirebaseStorage.getInstance(firestore.app)) : SyncTransport {
    override val originals: OriginalTransport = object : OriginalTransport {
        override val cacheKey = "firebase:${firestore.app.options.projectId}:${storage.reference.bucket}"
        private fun reference(record: Record): com.google.firebase.storage.StorageReference {
            require(record.accountId == accountId && record.kind == "attachment")
            return storage.reference.child("users/$accountId/attachments/${record.id}/original")
        }
        private fun hash(bytes: ByteArray) = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        private fun missing(error: Exception) = error is StorageException && error.errorCode == StorageException.ERROR_OBJECT_NOT_FOUND
        override suspend fun upload(record: Record, bytes: ByteArray) {
            uploadWithProgress(record, bytes) { }
        }
        override suspend fun uploadWithProgress(record: Record, bytes: ByteArray, progress: (Long) -> Unit) {
            val attachment = wireJson.decodeFromJsonElement(Attachment.serializer(), record.data)
            require(bytes.size.toLong() == attachment.size) { "Attachment size mismatch" }
            val target = reference(record)
            val sha256 = hash(bytes)
            try {
                val metadata = target.metadata.await()
                check(metadata.sizeBytes == bytes.size.toLong() && metadata.getCustomMetadata("sha256") == sha256) { "Attachment identity has different original bytes" }
                return
            } catch (error: Exception) { if (!missing(error)) throw error }
            val task = target.putBytes(bytes, StorageMetadata.Builder().setContentType(attachment.mimeType).setCustomMetadata("sha256", sha256).build())
            val listener = com.google.firebase.storage.OnProgressListener<com.google.firebase.storage.UploadTask.TaskSnapshot> { progress(it.bytesTransferred) }
            task.addOnProgressListener(listener)
            try { task.await() } catch (cancelled: kotlinx.coroutines.CancellationException) { task.cancel(); throw cancelled }
            finally { task.removeOnProgressListener(listener) }
        }
        override suspend fun download(record: Record): ByteArray {
            val target = reference(record)
            val attachment = wireJson.decodeFromJsonElement(Attachment.serializer(), record.data)
            val metadata = target.metadata.await()
            require(metadata.sizeBytes == attachment.size && metadata.sizeBytes <= 50L * 1024 * 1024) { "Attachment size mismatch" }
            val bytes = target.getBytes(50L * 1024 * 1024).await()
            check(bytes.size.toLong() == attachment.size && hash(bytes) == metadata.getCustomMetadata("sha256")) { "Attachment integrity check failed" }
            return bytes
        }
        override suspend fun remove(record: Record) {
            try { reference(record).delete().await() } catch (error: Exception) { if (!missing(error)) throw error }
        }
    }
    override suspend fun push(record: Record): Record {
        validate(record); require(record.accountId == accountId)
        val reference = firestore.collection("users").document(accountId).collection("records").document(record.id)
        return firestore.runTransaction { transaction ->
            val snapshot = transaction.get(reference)
            val remote = snapshot.data?.let { validate(wireJson.decodeFromString<Record>(JSONObject(it).toString())) }
            if (remote == null || compareVersions(record, remote) > 0) {
                @Suppress("UNCHECKED_CAST")
                val data = wireJson.encodeToJsonElement(record).firestoreValue() as Map<String, Any?>
                transaction.set(reference, data)
                record
            } else remote
        }.await()
    }
    override suspend fun pull(accountId: String): List<Record> {
        require(accountId == this.accountId)
        return firestore.collection("users").document(accountId).collection("records").get(Source.SERVER).await().documents.map { document ->
            validate(wireJson.decodeFromString<Record>(JSONObject(requireNotNull(document.data)).toString()))
        }
    }
}
