package app.nook.updates

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import androidx.core.content.edit
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.TimeUnit

internal class UpdatePreferences(context: Context) {
    private val values = context.applicationContext.getSharedPreferences("nook-android-updates", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    fun lastSuccessfulCheck(): Long = values.getLong(KEY_LAST_SUCCESS, 0)
    fun lastAttempt(): Long = values.getLong(KEY_LAST_ATTEMPT, 0)
    fun dismissedVersionCode(): Long = values.getLong(KEY_DISMISSED_VERSION, 0)
    fun cachedInfo(): UpdateInfo? = try { values.getString(KEY_CACHED_INFO, null)?.let { json.decodeFromString<UpdateInfo>(it) } }
        catch (_: Exception) { null }

    fun markAttempt(time: Long) { values.edit { putLong(KEY_LAST_ATTEMPT, time) } }
    fun saveSuccessfulCheck(time: Long, info: UpdateInfo) {
        values.edit { putLong(KEY_LAST_SUCCESS, time); putString(KEY_CACHED_INFO, json.encodeToString(info)) }
    }
    fun dismiss(versionCode: Long) { values.edit { putLong(KEY_DISMISSED_VERSION, versionCode) } }

    private companion object {
        const val KEY_LAST_SUCCESS = "last-success"
        const val KEY_LAST_ATTEMPT = "last-attempt"
        const val KEY_DISMISSED_VERSION = "dismissed-version-code"
        const val KEY_CACHED_INFO = "latest-update-info"
    }
}

internal class UpdateManager private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = UpdatePreferences(appContext)
    private val repository = UpdateRepository()
    private val installer = UpdateInstaller(appContext)
    private val installed = installedVersion(appContext)
    private val checkMutex = Mutex()
    private val mutableState = MutableStateFlow(cachedState(manual = false))
    val state = mutableState.asStateFlow()
    private var readyFile: File? = null
    private var downloadJob: Job? = null

    suspend fun checkAutomatically(): Boolean = check(force = false, manual = false)

    suspend fun checkManually() { check(force = true, manual = true) }

    private suspend fun check(force: Boolean, manual: Boolean): Boolean = checkMutex.withLock {
        if (mutableState.value.status == UpdateStatus.DOWNLOADING || mutableState.value.status == UpdateStatus.READY_TO_INSTALL) return@withLock true
        if (!manual && !hasInternetConnection()) return@withLock true
        val now = System.currentTimeMillis()
        if (!UpdatePolicy.shouldCheck(now, preferences.lastSuccessfulCheck(), preferences.lastAttempt(), manual)) return@withLock true
        preferences.markAttempt(now)
        val cached = preferences.cachedInfo()
        mutableState.value = UpdateUiState(UpdateStatus.CHECKING, installed, cached)
        try {
            val remote = repository.fetchLatest()
            preferences.saveSuccessfulCheck(System.currentTimeMillis(), remote)
            mutableState.value = if (UpdatePolicy.isUpdateAvailable(installed.versionCode, remote.versionCode)) {
                UpdateUiState(UpdateStatus.AVAILABLE, installed, remote, dismissed = !manual && preferences.dismissedVersionCode() == remote.versionCode)
            } else {
                UpdateUiState(UpdateStatus.UP_TO_DATE, installed, remote)
            }
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            if (manual) {
                mutableState.value = UpdateUiState(
                    status = UpdateStatus.ERROR,
                    currentVersion = installed,
                    info = preferences.cachedInfo(),
                    failureStage = UpdateFailureStage.CHECK,
                    message = "Could not check for updates. Check your connection and try again."
                )
            } else {
                mutableState.value = cachedState(manual = false)
            }
            false
        }
    }

    fun later() {
        val info = mutableState.value.info ?: return
        preferences.dismiss(info.versionCode)
        readyFile = null
        mutableState.value = UpdateUiState(UpdateStatus.AVAILABLE, installed, info, dismissed = true)
    }

    suspend fun download() {
        val info = mutableState.value.info ?: return
        if (!UpdatePolicy.isUpdateAvailable(installed.versionCode, info.versionCode)) return
        if (mutableState.value.status == UpdateStatus.DOWNLOADING) return
        val ownJob = currentCoroutineContext()[Job]
        downloadJob = ownJob
        readyFile = null
        mutableState.value = UpdateUiState(UpdateStatus.DOWNLOADING, installed, info, 0, info.apkSizeBytes)
        try {
            val apk = installer.downloadAndVerify(info) { downloaded, total ->
                mutableState.value = UpdateUiState(UpdateStatus.DOWNLOADING, installed, info, downloaded, total)
            }
            readyFile = apk
            mutableState.value = UpdateUiState(UpdateStatus.READY_TO_INSTALL, installed, info, info.apkSizeBytes, info.apkSizeBytes)
        } catch (cancelled: CancellationException) {
            mutableState.value = UpdateUiState(UpdateStatus.AVAILABLE, installed, info)
            throw cancelled
        } catch (error: Exception) {
            readyFile = null
            mutableState.value = UpdateUiState(
                status = UpdateStatus.ERROR,
                currentVersion = installed,
                info = info,
                failureStage = UpdateFailureStage.DOWNLOAD,
                message = error.message ?: "The update could not be downloaded. Try again."
            )
        } finally {
            downloadJob = null
        }
    }

    fun cancelDownload() { downloadJob?.cancel() }

    suspend fun install(): Boolean {
        val current = mutableState.value
        val info = current.info ?: return false
        val file = readyFile ?: return false
        if (current.status != UpdateStatus.READY_TO_INSTALL) return false
        return try {
            when (installer.install(file, info)) {
                InstallAction.STARTED -> true
                InstallAction.PERMISSION_REQUIRED -> {
                    mutableState.value = current.copy(message = "Allow Nook to install apps in Android settings, then return and tap Install again.")
                    false
                }
            }
        } catch (error: Exception) {
            file.delete()
            readyFile = null
            mutableState.value = UpdateUiState(
                status = UpdateStatus.ERROR,
                currentVersion = installed,
                info = info,
                failureStage = UpdateFailureStage.DOWNLOAD,
                message = error.message ?: "Android could not open the installer. Download the update again."
            )
            false
        }
    }

    private fun cachedState(manual: Boolean): UpdateUiState {
        val cached = preferences.cachedInfo() ?: return UpdateUiState(UpdateStatus.IDLE, installed)
        return if (UpdatePolicy.isUpdateAvailable(installed.versionCode, cached.versionCode)) {
            UpdateUiState(UpdateStatus.AVAILABLE, installed, cached, dismissed = !manual && preferences.dismissedVersionCode() == cached.versionCode)
        } else {
            UpdateUiState(UpdateStatus.UP_TO_DATE, installed, cached)
        }
    }

    private fun hasInternetConnection(): Boolean {
        return try {
            val manager = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
            val network = manager.activeNetwork ?: return false
            manager.getNetworkCapabilities(network)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        } catch (_: SecurityException) { false }
    }

    companion object {
        @Volatile private var instance: UpdateManager? = null
        fun from(context: Context): UpdateManager = instance ?: synchronized(this) {
            instance ?: UpdateManager(context.applicationContext).also { instance = it }
        }
    }
}

internal class UpdateWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = try {
        if (UpdateManager.from(applicationContext).checkAutomatically()) Result.success() else Result.retry()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        Result.retry()
    }
}

internal fun scheduleUpdateChecks(context: Context) {
    try {
        val request = PeriodicWorkRequestBuilder<UpdateWorker>(6, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
            .addTag("nook-update-check")
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
            "nook-android-update-check",
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    } catch (_: Exception) {
        // Update scheduling must never interfere with local-first startup.
    }
}

@Suppress("DEPRECATION")
private fun installedVersion(context: Context): InstalledVersion {
    val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
    val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) packageInfo.longVersionCode else packageInfo.versionCode.toLong()
    return InstalledVersion(packageInfo.versionName.orEmpty(), versionCode)
}
