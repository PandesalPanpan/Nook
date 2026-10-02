package app.nook.updates

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.security.MessageDigest
import java.util.Locale
import kotlin.coroutines.coroutineContext

internal data class ApkTransferResult(val file: File, val sha256: String, val bytes: Long)

internal class ApkTransfer(
    private val cacheDirectory: File,
    private val connections: HttpConnectionFactory = DefaultHttpConnectionFactory
) {
    suspend fun download(info: UpdateInfo, onProgress: suspend (downloaded: Long, total: Long) -> Unit): ApkTransferResult = withContext(Dispatchers.IO) {
        if (!UpdateUrls.isReleaseAsset(info.downloadUrl)) throw UpdateDataException("The APK download URL is not a Nook GitHub release asset.")
        if (!SAFE_APK_NAME.matches(info.apk)) throw UpdateDataException("The APK asset name is invalid.")
        if (info.apkSizeBytes !in 1..MAX_APK_BYTES) throw UpdateDataException("The APK size is invalid.")
        val directory = File(cacheDirectory, "updates").canonicalFile
        if (!directory.exists() && !directory.mkdirs()) throw UpdateDataException("The Nook update cache could not be created.")
        if (!directory.isDirectory) throw UpdateDataException("The Nook update cache is unavailable.")
        val target = File(directory, info.apk).canonicalFile
        val partial = File(directory, "${info.apk}.part").canonicalFile
        if (target.parentFile != directory || partial.parentFile != directory) throw UpdateDataException("The update file path is invalid.")
        target.delete()
        partial.delete()
        var completed = false
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var currentUrl = info.downloadUrl
            var totalBytes = -1L
            var downloaded = 0L
            var receivedBody = false
            for (redirect in 0..5) {
                val connection = connections.open(currentUrl)
                try {
                    connection.instanceFollowRedirects = false
                    connection.connectTimeout = CONNECT_TIMEOUT_MS
                    connection.readTimeout = READ_TIMEOUT_MS
                    connection.setRequestProperty("Accept", "application/vnd.android.package-archive,application/octet-stream")
                    connection.setRequestProperty("User-Agent", "Nook-Android-Updater")
                    val status = connection.responseCode
                    if (status in 300..399) {
                        if (redirect == 5) throw UpdateDataException("The APK download redirected too many times.")
                        val location = connection.getHeaderField("Location") ?: throw UpdateDataException("The APK download redirect is incomplete.")
                        val next = try { URI(currentUrl).resolve(location).toString() }
                        catch (error: Exception) { throw UpdateDataException("The APK download redirect is invalid.", error) }
                        if (!UpdateUrls.isAllowedAssetRedirect(next)) throw UpdateDataException("The APK download redirected to an untrusted host.")
                        currentUrl = next
                        continue
                    }
                    if (status !in 200..299) throw java.io.IOException("GitHub returned HTTP $status while downloading the APK.")
                    if (connection.contentType?.startsWith("text/html", ignoreCase = true) == true) throw UpdateDataException("The APK download returned a web page instead of an APK.")
                    totalBytes = connection.contentLengthLong
                    if (totalBytes > MAX_APK_BYTES) throw UpdateDataException("The APK is larger than the allowed download size.")
                    if (totalBytes > 0 && totalBytes != info.apkSizeBytes) throw UpdateDataException("The APK size does not match the GitHub release asset.")
                    onProgress(0, totalBytes)
                    BufferedInputStream(connection.inputStream).use { input ->
                        BufferedOutputStream(FileOutputStream(partial, false)).use { output ->
                            val buffer = ByteArray(32 * 1024)
                            while (true) {
                                coroutineContext.ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                downloaded += count
                                if (downloaded > MAX_APK_BYTES || downloaded > info.apkSizeBytes) throw UpdateDataException("The APK download exceeded its declared size.")
                                output.write(buffer, 0, count)
                                digest.update(buffer, 0, count)
                                onProgress(downloaded, totalBytes)
                            }
                        }
                    }
                    receivedBody = true
                    break
                } finally {
                    connection.disconnect()
                }
            }
            if (!receivedBody) throw UpdateDataException("The APK download did not complete.")
            coroutineContext.ensureActive()
            if (downloaded != info.apkSizeBytes) throw UpdateDataException("The APK download was incomplete.")
            if (!hasApkZipSignature(partial)) throw UpdateDataException("The downloaded file is not a valid APK archive.")
            val actualSha = digest.digest().toHex()
            if (!actualSha.equals(info.sha256, ignoreCase = true)) throw UpdateDataException("The APK checksum did not match the update manifest.")
            if (!partial.renameTo(target)) throw UpdateDataException("The verified APK could not be moved into the update cache.")
            completed = true
            onProgress(downloaded, downloaded)
            ApkTransferResult(target, actualSha, downloaded)
        } finally {
            partial.delete()
            if (!completed) target.delete()
        }
    }

    private fun hasApkZipSignature(file: File): Boolean = try {
        file.inputStream().use { input -> input.read() == 0x50 && input.read() == 0x4b && input.read() == 0x03 && input.read() == 0x04 }
    } catch (_: Exception) { false }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(Locale.US, it) }

    private companion object {
        val SAFE_APK_NAME = Regex("^nook-v[0-9]+\\.[0-9]+\\.[0-9]+\\.apk$")
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 30_000
    }
}
