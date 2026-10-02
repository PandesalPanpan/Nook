package app.nook.updates

import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.security.MessageDigest

class ApkTransferTest {
    @get:Rule val temporary = TemporaryFolder()
    private val assetUrl = "https://github.com/PandesalPanpan/Nook/releases/download/v0.2.0/nook-v0.2.0.apk"

    private fun apkBytes(): ByteArray = byteArrayOf(0x50, 0x4b, 0x03, 0x04) + ByteArray(60) { it.toByte() }

    private fun info(bytes: ByteArray, url: String = assetUrl, sha: String = digest(bytes)) = UpdateInfo(
        versionName = "0.2.0", versionCode = 2, packageName = "app.nook", apk = "nook-v0.2.0.apk",
        sha256 = sha, minimumSdk = 26, publishedAt = "2026-10-02T00:00:00Z",
        releaseUrl = "https://github.com/PandesalPanpan/Nook/releases/tag/v0.2.0", releaseNotes = "",
        downloadUrl = url, apkSizeBytes = bytes.size.toLong()
    )

    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test fun downloadReportsProgressAndMovesOnlyVerifiedBytes() = runTest {
        val bytes = apkBytes()
        val transfer = ApkTransfer(temporary.root, HttpConnectionFactory { url -> FakeConnection(URL(url), body = bytes) })
        val progress = mutableListOf<Pair<Long, Long>>()
        val result = transfer.download(info(bytes)) { downloaded, total -> progress += downloaded to total }
        assertEquals(bytes.size.toLong(), result.bytes)
        assertEquals(digest(bytes), result.sha256)
        assertTrue(result.file.isFile)
        assertEquals(bytes.toList(), result.file.readBytes().toList())
        assertTrue(progress.first().first == 0L)
        assertTrue(progress.last().first == bytes.size.toLong())
        assertFalse(java.io.File(result.file.parentFile, "${result.file.name}.part").exists())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun cancellationRemovesThePartialFile() = runTest {
        val bytes = apkBytes()
        lateinit var transferJob: Job
        val factory = HttpConnectionFactory { url ->
            FakeConnection(URL(url), streamFactory = {
                object : InputStream() {
                    private var sent = false
                    override fun read(): Int = -1
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                        if (!sent) {
                            sent = true
                            val count = minOf(length, bytes.size)
                            bytes.copyInto(buffer, offset, 0, count)
                            return count
                        }
                        transferJob.cancel()
                        return -1
                    }
                }
            })
        }
        val transfer = ApkTransfer(temporary.root, factory)
        transferJob = launch { transfer.download(info(bytes)) { _, _ -> } }
        runCurrent()
        transferJob.join()
        assertTrue(transferJob.isCancelled)
        val directory = java.io.File(temporary.root, "updates")
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }

    @Test fun timeoutAndNetworkFailureAreRecoverableAndCleanUp() {
        val bytes = apkBytes()
        val timedOut = ApkTransfer(temporary.root, HttpConnectionFactory { url ->
            FakeConnection(URL(url), responseFailure = SocketTimeoutException("timeout"))
        })
        assertThrows(SocketTimeoutException::class.java) {
            kotlinx.coroutines.runBlocking { timedOut.download(info(bytes)) { _, _ -> } }
        }
        val offline = ApkTransfer(temporary.root, HttpConnectionFactory { throw IOException("offline") })
        assertThrows(IOException::class.java) {
            kotlinx.coroutines.runBlocking { offline.download(info(bytes)) { _, _ -> } }
        }
        assertTrue(java.io.File(temporary.root, "updates").listFiles().orEmpty().isEmpty())
    }

    @Test fun interruptedStreamRemovesPartialBytes() {
        val bytes = apkBytes()
        val transfer = ApkTransfer(temporary.root, HttpConnectionFactory { url ->
            FakeConnection(URL(url), contentLength = bytes.size.toLong(), streamFactory = {
                object : InputStream() {
                    private var sent = false
                    override fun read(): Int = -1
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                        if (!sent) {
                            sent = true
                            val count = minOf(10, length)
                            bytes.copyInto(buffer, offset, 0, count)
                            return count
                        }
                        throw IOException("interrupted")
                    }
                }
            })
        })
        assertThrows(IOException::class.java) {
            kotlinx.coroutines.runBlocking { transfer.download(info(bytes)) { _, _ -> } }
        }
        assertTrue(java.io.File(temporary.root, "updates").listFiles().orEmpty().isEmpty())
    }

    @Test fun checksumMismatchAndInvalidArchiveAreRejected() {
        val bytes = apkBytes()
        val mismatch = ApkTransfer(temporary.root, HttpConnectionFactory { url -> FakeConnection(URL(url), body = bytes) })
        assertThrows(UpdateDataException::class.java) {
            kotlinx.coroutines.runBlocking { mismatch.download(info(bytes, sha = "0".repeat(64))) { _, _ -> } }
        }
        val invalid = "not-an-apk".toByteArray()
        val invalidTransfer = ApkTransfer(temporary.root, HttpConnectionFactory { url -> FakeConnection(URL(url), body = invalid) })
        assertThrows(UpdateDataException::class.java) {
            kotlinx.coroutines.runBlocking { invalidTransfer.download(info(invalid)) { _, _ -> } }
        }
        assertTrue(java.io.File(temporary.root, "updates").listFiles().orEmpty().isEmpty())
    }

    @Test fun unsafeUrlRedirectAndUnexpectedLengthAreRejected() {
        val bytes = apkBytes()
        var opened = 0
        val redirect = ApkTransfer(temporary.root, HttpConnectionFactory { url ->
            opened += 1
            FakeConnection(URL(url), statusCode = 302, headers = mapOf("Location" to "https://evil.example/file.apk"))
        })
        assertThrows(UpdateDataException::class.java) {
            kotlinx.coroutines.runBlocking { redirect.download(info(bytes)) { _, _ -> } }
        }
        assertEquals(1, opened)

        val mismatch = ApkTransfer(temporary.root, HttpConnectionFactory { url ->
            FakeConnection(URL(url), body = bytes, contentLength = bytes.size.toLong() + 1)
        })
        assertThrows(UpdateDataException::class.java) {
            kotlinx.coroutines.runBlocking { mismatch.download(info(bytes)) { _, _ -> } }
        }
        assertThrows(UpdateDataException::class.java) {
            kotlinx.coroutines.runBlocking { mismatch.download(info(bytes, url = "http://example.com/file.apk")) { _, _ -> } }
        }
        assertTrue(java.io.File(temporary.root, "updates").listFiles().orEmpty().isEmpty())
    }

    private class FakeConnection(
        url: URL,
        private val statusCode: Int = 200,
        private val body: ByteArray = ByteArray(0),
        private val headers: Map<String, String> = emptyMap(),
        private val contentLength: Long = body.size.toLong(),
        private val responseFailure: IOException? = null,
        private val streamFactory: () -> InputStream = { ByteArrayInputStream(body) }
    ) : HttpURLConnection(url) {
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy(): Boolean = false
        override fun getResponseCode(): Int { responseFailure?.let { throw it }; return statusCode }
        override fun getHeaderField(name: String): String? = headers[name]
        override fun getContentLengthLong(): Long = contentLength
        override fun getContentType(): String = "application/octet-stream"
        override fun getInputStream(): InputStream = streamFactory()
    }
}
