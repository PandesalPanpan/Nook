package app.nook.updates

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.net.toUri
import androidx.core.content.FileProvider
import app.nook.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.Locale

internal enum class InstallAction { STARTED, PERMISSION_REQUIRED }

internal class UpdateInstaller(context: Context, connections: HttpConnectionFactory = DefaultHttpConnectionFactory) {
    private val appContext = context.applicationContext
    private val transfer = ApkTransfer(appContext.cacheDir, connections)
    private val packageManager = appContext.packageManager
    private val expectedCertificate = BuildConfig.NOOK_RELEASE_CERT_SHA256.normalizedFingerprint()

    suspend fun downloadAndVerify(info: UpdateInfo, onProgress: suspend (Long, Long) -> Unit): File {
        val result = transfer.download(info, onProgress)
        try {
            val metadata = inspect(result.file)
            ApkMetadataValidator.validate(metadata, info, installedVersion().versionCode, expectedCertificate, result.sha256)
            return result.file
        } catch (error: Exception) {
            result.file.delete()
            throw error
        }
    }

    suspend fun install(file: File, info: UpdateInfo): InstallAction {
        withContext(Dispatchers.IO) {
            val verifiedFile = requireSafeFile(file, info)
            val actualSha = sha256(verifiedFile)
            val metadata = inspect(verifiedFile)
            ApkMetadataValidator.validate(metadata, info, installedVersion().versionCode, expectedCertificate, actualSha)
        }
        if (!packageManager.canRequestPackageInstalls()) {
            val settingsIntent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${appContext.packageName}".toUri())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            appContext.startActivity(settingsIntent)
            return InstallAction.PERMISSION_REQUIRED
        }
        val safeFile = requireSafeFile(file, info)
        val contentUri = FileProvider.getUriForFile(appContext, "${appContext.packageName}.files", safeFile)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(contentUri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        appContext.startActivity(intent)
        return InstallAction.STARTED
    }

    private fun requireSafeFile(file: File, info: UpdateInfo): File {
        val directory = File(appContext.cacheDir, "updates").canonicalFile
        val candidate = file.canonicalFile
        if (candidate.parentFile != directory || candidate.name != info.apk || !candidate.isFile || candidate.length() !in 1..MAX_APK_BYTES) {
            throw UpdateDataException("The verified Nook update is no longer available.")
        }
        if (candidate.length() != info.apkSizeBytes) throw UpdateDataException("The verified Nook update has changed size.")
        return candidate
    }

    private fun inspect(file: File): ApkMetadata {
        if (!file.isFile || !file.canRead() || file.length() <= 0 || file.length() > MAX_APK_BYTES) throw UpdateDataException("The downloaded file is not a readable APK.")
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else legacySignatureFlag()
        val archive = packageManager.getPackageArchiveInfo(file.absolutePath, flags) ?: throw UpdateDataException("Android could not read the downloaded APK.")
        return ApkMetadata(
            packageName = archive.packageName ?: "",
            versionName = archive.versionName,
            versionCode = archive.longVersionCodeCompat(),
            certificateSha256 = archive.signerSha256().normalizedFingerprint()
        )
    }

    private fun PackageInfo.signerSha256(): String {
        val signerBytes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            signingInfo?.apkContentsSigners?.map { it.toByteArray() }.orEmpty()
        } else {
            legacySignatures()
        }
        if (signerBytes.size != 1) throw UpdateDataException("The downloaded APK does not have one Nook release signer.")
        return MessageDigest.getInstance("SHA-256").digest(signerBytes.single()).toHex()
    }

    private fun installedVersion(): InstalledVersion {
        val info = try { packageManager.getPackageInfo(appContext.packageName, 0) }
        catch (error: PackageManager.NameNotFoundException) { throw UpdateDataException("Android could not verify the installed Nook version.", error) }
        return InstalledVersion(info.versionName.orEmpty(), info.longVersionCodeCompat())
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(32 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().toHex()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(Locale.US, it) }
    private fun String.normalizedFingerprint(): String = filterNot { it == ':' || it.isWhitespace() }.uppercase(Locale.US)

    @Suppress("DEPRECATION")
    private fun PackageInfo.legacySignatures(): List<ByteArray> = signatures?.map { it.toByteArray() }.orEmpty()

    @Suppress("DEPRECATION")
    private fun legacySignatureFlag(): Int = PackageManager.GET_SIGNATURES

    @Suppress("DEPRECATION")
    private fun PackageInfo.longVersionCodeCompat(): Long = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) longVersionCode else versionCode.toLong()
}
