package app.nook.updates

import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

internal fun interface HttpConnectionFactory {
    fun open(url: String): HttpURLConnection
}

internal object DefaultHttpConnectionFactory : HttpConnectionFactory {
    override fun open(url: String): HttpURLConnection = URL(url).openConnection() as HttpURLConnection
}

internal class UpdateRepository(
    private val connections: HttpConnectionFactory = DefaultHttpConnectionFactory
) {
    suspend fun fetchLatest(): UpdateInfo = withContext(Dispatchers.IO) {
        val releaseBody = getBytes(LATEST_RELEASE_API, 512 * 1024, "application/vnd.github+json", allowReleaseRedirects = false)
        val release = LatestReleaseParser.parse(String(releaseBody, Charsets.UTF_8))
        val manifestBody = getBytes(release.updateManifest.downloadUrl, MAX_MANIFEST_BYTES, "application/json", allowReleaseRedirects = true)
        val manifest = UpdateManifestParser.parse(String(manifestBody, Charsets.UTF_8))
        if (manifest.versionName != release.tagName.removePrefix("v")) throw UpdateDataException("The update manifest version does not match the GitHub release tag.")
        if (manifest.releaseUrl != release.releaseUrl) throw UpdateDataException("The update manifest link does not match the GitHub release.")
        if (manifest.apk != release.apk.name) throw UpdateDataException("The update manifest does not name the APK asset in this release.")
        if (manifest.minimumSdk > Build.VERSION.SDK_INT) throw UpdateDataException("This Nook update requires a newer version of Android.")
        UpdateInfo(
            versionName = manifest.versionName,
            versionCode = manifest.versionCode,
            packageName = manifest.packageName,
            apk = manifest.apk,
            sha256 = manifest.sha256.lowercase(),
            minimumSdk = manifest.minimumSdk,
            publishedAt = manifest.publishedAt,
            releaseUrl = manifest.releaseUrl,
            releaseNotes = release.releaseNotes,
            downloadUrl = release.apk.downloadUrl,
            apkSizeBytes = release.apk.sizeBytes
        )
    }

    private fun getBytes(initialUrl: String, maxBytes: Int, accept: String, allowReleaseRedirects: Boolean): ByteArray {
        if (initialUrl != LATEST_RELEASE_API && !UpdateUrls.isReleaseAsset(initialUrl)) throw UpdateDataException("The GitHub request URL is not trusted.")
        var currentUrl = initialUrl
        for (redirect in 0..5) {
            val connection = connections.open(currentUrl)
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = CONNECT_TIMEOUT_MS
                connection.readTimeout = READ_TIMEOUT_MS
                connection.setRequestProperty("Accept", accept)
                connection.setRequestProperty("User-Agent", "Nook-Android-Updater")
                if (initialUrl == LATEST_RELEASE_API) connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                val status = connection.responseCode
                if (status in 300..399) {
                    if (!allowReleaseRedirects || redirect == 5) throw UpdateDataException("GitHub redirected the update request unexpectedly.")
                    val location = connection.getHeaderField("Location") ?: throw UpdateDataException("GitHub returned an incomplete redirect.")
                    val next = try { URI(currentUrl).resolve(location).toString() }
                    catch (error: Exception) { throw UpdateDataException("GitHub returned an invalid redirect.", error) }
                    if (!UpdateUrls.isAllowedAssetRedirect(next)) throw UpdateDataException("GitHub redirected the update to an untrusted host.")
                    currentUrl = next
                    continue
                }
                if (status !in 200..299) throw IOException("GitHub returned HTTP $status.")
                if (connection.contentType?.startsWith("text/html", ignoreCase = true) == true) throw UpdateDataException("GitHub returned a web page instead of update metadata.")
                val declared = connection.contentLengthLong
                if (declared > maxBytes) throw UpdateDataException("GitHub returned an oversized response.")
                val output = ByteArrayOutputStream(minOf(maxBytes, maxOf(1024, declared.coerceAtLeast(0).toInt())))
                connection.inputStream.use { input ->
                    val buffer = ByteArray(8192)
                    var total = 0
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > maxBytes) throw UpdateDataException("GitHub returned an oversized response.")
                        output.write(buffer, 0, count)
                    }
                }
                if (output.size() == 0) throw UpdateDataException("GitHub returned an empty response.")
                return output.toByteArray()
            } finally {
                connection.disconnect()
            }
        }
        throw UpdateDataException("GitHub redirected the update request too many times.")
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 12_000
        const val READ_TIMEOUT_MS = 20_000
    }
}
