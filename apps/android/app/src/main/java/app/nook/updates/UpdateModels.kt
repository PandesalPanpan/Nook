package app.nook.updates

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.net.URI
import java.time.Instant

internal const val NOOK_PACKAGE_NAME = "app.nook"
internal const val GITHUB_OWNER = "PandesalPanpan"
internal const val GITHUB_REPOSITORY = "Nook"
internal const val LATEST_RELEASE_API = "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPOSITORY/releases/latest"
internal const val MAX_MANIFEST_BYTES = 32 * 1024
internal const val MAX_RELEASE_NOTES_CHARS = 12_000
internal const val MAX_APK_BYTES = 180L * 1024L * 1024L

@Serializable
data class UpdateManifest(
    val schemaVersion: Int,
    val versionName: String,
    val versionCode: Long,
    val packageName: String,
    val apk: String,
    val sha256: String,
    val minimumSdk: Int,
    val publishedAt: String,
    val releaseUrl: String
)

@Serializable
data class UpdateInfo(
    val versionName: String,
    val versionCode: Long,
    val packageName: String,
    val apk: String,
    val sha256: String,
    val minimumSdk: Int,
    val publishedAt: String,
    val releaseUrl: String,
    val releaseNotes: String,
    val downloadUrl: String,
    val apkSizeBytes: Long
)

data class InstalledVersion(val versionName: String, val versionCode: Long)

enum class UpdateStatus { IDLE, CHECKING, UP_TO_DATE, AVAILABLE, DOWNLOADING, READY_TO_INSTALL, ERROR }

enum class UpdateFailureStage { NONE, CHECK, DOWNLOAD }

data class UpdateUiState(
    val status: UpdateStatus = UpdateStatus.IDLE,
    val currentVersion: InstalledVersion = InstalledVersion("", 0),
    val info: UpdateInfo? = null,
    val downloadedBytes: Long = 0,
    val totalBytes: Long = -1,
    val failureStage: UpdateFailureStage = UpdateFailureStage.NONE,
    val message: String? = null,
    val dismissed: Boolean = false
) {
    val progress: Float?
        get() = if (totalBytes > 0) (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else null
}

internal data class ReleaseAsset(val name: String, val downloadUrl: String, val sizeBytes: Long)

internal data class LatestReleaseAssets(
    val tagName: String,
    val publishedAt: String,
    val releaseUrl: String,
    val releaseNotes: String,
    val updateManifest: ReleaseAsset,
    val apk: ReleaseAsset
)

internal class UpdateDataException(message: String, cause: Throwable? = null) : Exception(message, cause)

internal object UpdateManifestParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = false }
    private val versionNamePattern = Regex("^(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)$")
    private val sha256Pattern = Regex("^[a-fA-F0-9]{64}$")
    private val apkPattern = Regex("^nook-v(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.apk$")

    fun parse(raw: String): UpdateManifest {
        if (raw.toByteArray(Charsets.UTF_8).size > MAX_MANIFEST_BYTES) throw UpdateDataException("The update manifest is too large.")
        val root = try { json.parseToJsonElement(raw).jsonObject }
        catch (error: Exception) { throw UpdateDataException("The update manifest is not valid JSON.", error) }
        val manifest = try {
            UpdateManifest(
                schemaVersion = root.requiredInt("schemaVersion"),
                versionName = root.requiredString("versionName", 40),
                versionCode = root.requiredLong("versionCode"),
                packageName = root.requiredString("packageName", 200),
                apk = root.requiredString("apk", 200),
                sha256 = root.requiredString("sha256", 64),
                minimumSdk = root.requiredInt("minimumSdk"),
                publishedAt = root.requiredString("publishedAt", 64),
                releaseUrl = root.requiredString("releaseUrl", 500)
            )
        } catch (error: Exception) {
            if (error is UpdateDataException) throw error
            throw UpdateDataException("The update manifest is missing a required field or has the wrong field type.", error)
        }
        if (manifest.schemaVersion != 1) throw UpdateDataException("This update manifest uses an unsupported schema version.")
        if (!versionNamePattern.matches(manifest.versionName)) throw UpdateDataException("The update version name is invalid.")
        if (manifest.versionCode !in 1..Int.MAX_VALUE.toLong()) throw UpdateDataException("The update version code is invalid.")
        if (manifest.packageName != NOOK_PACKAGE_NAME) throw UpdateDataException("This release is for a different Android package.")
        if (!apkPattern.matches(manifest.apk) || manifest.apk != "nook-v${manifest.versionName}.apk") throw UpdateDataException("The update APK asset name is invalid.")
        if (!sha256Pattern.matches(manifest.sha256)) throw UpdateDataException("The update APK checksum is invalid.")
        if (manifest.minimumSdk < 26 || manifest.minimumSdk > 100) throw UpdateDataException("The update minimum Android version is invalid.")
        try { Instant.parse(manifest.publishedAt) }
        catch (error: Exception) { throw UpdateDataException("The update publication date is invalid.", error) }
        if (!UpdateUrls.isReleasePage(manifest.releaseUrl, manifest.versionName)) throw UpdateDataException("The update release link is invalid.")
        return manifest
    }

    private fun JsonObject.requiredString(name: String, maxLength: Int): String {
        val value = this[name] as? JsonPrimitive ?: throw UpdateDataException("The update manifest field '$name' is missing.")
        if (!value.isString || value.content.isBlank() || value.content.length > maxLength) throw UpdateDataException("The update manifest field '$name' is invalid.")
        return value.content
    }

    private fun JsonObject.requiredInt(name: String): Int = (this[name] as? JsonPrimitive)?.intOrNull
        ?: throw UpdateDataException("The update manifest field '$name' is invalid.")

    private fun JsonObject.requiredLong(name: String): Long = (this[name] as? JsonPrimitive)?.longOrNull
        ?: throw UpdateDataException("The update manifest field '$name' is invalid.")
}

internal object LatestReleaseParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = false }
    private val tagPattern = Regex("^v(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)$")

    fun parse(raw: String): LatestReleaseAssets {
        if (raw.toByteArray(Charsets.UTF_8).size > 512 * 1024) throw UpdateDataException("GitHub returned an oversized release response.")
        val root = try { json.parseToJsonElement(raw).jsonObject }
        catch (error: Exception) { throw UpdateDataException("GitHub returned invalid release data.", error) }
        if (root.booleanField("draft") != false) throw UpdateDataException("GitHub did not return a published release.")
        if (root.booleanField("prerelease") != false) throw UpdateDataException("Preview releases are not supported by the Nook updater.")
        val tag = root.stringField("tag_name", 80)
        if (!tagPattern.matches(tag)) throw UpdateDataException("The GitHub release tag is invalid.")
        val versionName = tag.removePrefix("v")
        val publishedAt = root.stringField("published_at", 64)
        try { Instant.parse(publishedAt) }
        catch (error: Exception) { throw UpdateDataException("GitHub returned an invalid release date.", error) }
        val releaseUrl = root.stringField("html_url", 500)
        if (!UpdateUrls.isReleasePage(releaseUrl, versionName)) throw UpdateDataException("GitHub returned an unexpected release link.")
        val releaseNotes = (root["body"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty().take(MAX_RELEASE_NOTES_CHARS)
        val assets = try { root["assets"]?.jsonArray ?: throw UpdateDataException("The GitHub release has no assets.") }
        catch (error: Exception) {
            if (error is UpdateDataException) throw error
            throw UpdateDataException("GitHub returned invalid release assets.", error)
        }
        val parsedAssets = assets.map { element ->
            val asset = try { element.jsonObject } catch (error: Exception) { throw UpdateDataException("GitHub returned an invalid release asset.", error) }
            val name = asset.stringField("name", 200)
            val url = asset.stringField("browser_download_url", 2_000)
            val size = asset.longField("size")
            if (size <= 0 || size > MAX_APK_BYTES) throw UpdateDataException("A GitHub release asset has an invalid size.")
            if (!UpdateUrls.isReleaseAsset(url)) throw UpdateDataException("A GitHub release asset URL is not trusted.")
            if (asset.stringFieldOptional("state")?.let { it != "uploaded" } == true) throw UpdateDataException("A GitHub release asset is not ready.")
            ReleaseAsset(name, url, size)
        }
        val updateAssets = parsedAssets.filter { it.name == "update.json" }
        if (updateAssets.size != 1) throw UpdateDataException("The GitHub release must contain exactly one update.json asset.")
        if (updateAssets.single().sizeBytes > MAX_MANIFEST_BYTES) throw UpdateDataException("The update manifest asset is too large.")
        val expectedApkName = "nook-v$versionName.apk"
        val apkAssets = parsedAssets.filter { it.name == expectedApkName }
        if (apkAssets.size != 1) throw UpdateDataException("The GitHub release must contain exactly one $expectedApkName asset.")
        return LatestReleaseAssets(tag, publishedAt, releaseUrl, releaseNotes, updateAssets.single(), apkAssets.single())
    }

    private fun JsonObject.booleanField(name: String): Boolean? = (this[name] as? JsonPrimitive)?.booleanOrNull
    private fun JsonObject.stringField(name: String, max: Int): String = stringFieldOptional(name)
        ?.takeIf { it.isNotBlank() && it.length <= max }
        ?: throw UpdateDataException("GitHub release field '$name' is missing or invalid.")
    private fun JsonObject.stringFieldOptional(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.longField(name: String): Long = (this[name] as? JsonPrimitive)?.longOrNull
        ?: throw UpdateDataException("GitHub release field '$name' is invalid.")
}

internal object UpdateUrls {
    fun isLatestApi(value: String): Boolean = value == LATEST_RELEASE_API

    fun isReleaseAsset(value: String): Boolean = parseHttps(value)?.let { uri ->
        uri.host.equals("github.com", ignoreCase = true) && uri.port == -1 &&
            uri.rawPath.startsWith("/$GITHUB_OWNER/$GITHUB_REPOSITORY/releases/download/") && uri.rawQuery == null &&
            uri.rawFragment == null && uri.rawPath.split('/').none { it == "." || it == ".." }
    } == true

    fun isAllowedAssetRedirect(value: String): Boolean = parseHttps(value)?.let { uri ->
        uri.host.lowercase() in setOf("github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com") && uri.port == -1 &&
            uri.rawPath.isNotBlank() && uri.rawPath.split('/').none { it == "." || it == ".." }
    } == true

    fun isReleasePage(value: String, versionName: String): Boolean = parseHttps(value)?.let { uri ->
        uri.host.equals("github.com", ignoreCase = true) && uri.port == -1 &&
            uri.rawPath == "/$GITHUB_OWNER/$GITHUB_REPOSITORY/releases/tag/v$versionName" && uri.rawQuery == null && uri.rawFragment == null
    } == true

    private fun parseHttps(value: String): URI? = try {
        URI(value).takeIf { it.scheme.equals("https", ignoreCase = true) && it.userInfo == null && it.host != null }
    } catch (_: Exception) { null }
}

internal object UpdatePolicy {
    const val AUTOMATIC_SUCCESS_INTERVAL_MS = 6 * 60 * 60 * 1000L
    const val AUTOMATIC_FAILURE_RETRY_INTERVAL_MS = 15 * 60 * 1000L

    fun isUpdateAvailable(installedVersionCode: Long, remoteVersionCode: Long): Boolean = remoteVersionCode > installedVersionCode

    fun shouldCheckAutomatically(now: Long, lastSuccessfulCheck: Long, lastAttempt: Long): Boolean {
        if (now < lastSuccessfulCheck || now < lastAttempt) return true
        val successDue = lastSuccessfulCheck <= 0 || now - lastSuccessfulCheck >= AUTOMATIC_SUCCESS_INTERVAL_MS
        val retryDue = lastAttempt <= 0 || now - lastAttempt >= AUTOMATIC_FAILURE_RETRY_INTERVAL_MS
        return successDue && retryDue
    }

    fun shouldCheck(now: Long, lastSuccessfulCheck: Long, lastAttempt: Long, manual: Boolean): Boolean =
        manual || shouldCheckAutomatically(now, lastSuccessfulCheck, lastAttempt)

    fun shouldShowUpdate(remoteVersionCode: Long, installedVersionCode: Long, dismissedVersionCode: Long, manual: Boolean): Boolean =
        isUpdateAvailable(installedVersionCode, remoteVersionCode) && (manual || dismissedVersionCode != remoteVersionCode)
}

internal data class ApkMetadata(val packageName: String, val versionName: String?, val versionCode: Long, val certificateSha256: String)

internal object ApkMetadataValidator {
    fun validate(
        metadata: ApkMetadata,
        update: UpdateInfo,
        installedVersionCode: Long,
        expectedCertificateSha256: String,
        actualSha256: String
    ) {
        if (!actualSha256.equals(update.sha256, ignoreCase = true)) throw UpdateDataException("The APK checksum did not match the update manifest.")
        if (metadata.packageName != NOOK_PACKAGE_NAME || metadata.packageName != update.packageName) throw UpdateDataException("The downloaded APK is not a Nook package.")
        if (metadata.versionCode != update.versionCode || metadata.versionName != update.versionName) throw UpdateDataException("The downloaded APK version does not match the update manifest.")
        if (!UpdatePolicy.isUpdateAvailable(installedVersionCode, metadata.versionCode)) throw UpdateDataException("The downloaded APK is not newer than the installed Nook app.")
        if (!metadata.certificateSha256.equals(expectedCertificateSha256, ignoreCase = true)) throw UpdateDataException("The downloaded APK was signed with an unexpected Nook certificate.")
    }
}
