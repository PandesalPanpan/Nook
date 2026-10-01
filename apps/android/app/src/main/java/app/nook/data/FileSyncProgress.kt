package app.nook.data

import androidx.work.Data
import androidx.work.workDataOf

enum class FileSyncPhase { CHECKING, UPLOADING, DOWNLOADING, REMOVING }
data class FileSyncProgress(val total: Int, val checked: Int = 0, val failed: Int = 0,
    val phase: FileSyncPhase = FileSyncPhase.CHECKING, val filename: String? = null,
    val transferred: Long? = null, val size: Long? = null)

/** Counts include cached integrity checks and deletion cleanup, not only byte transfers. */
fun fileSyncProgressText(progress: FileSyncProgress, active: Boolean): String {
    val count = "${progress.checked} of ${progress.total} files checked"
    val retry = if(progress.failed > 0) " · ${progress.failed} ${if(progress.failed == 1) "file needs" else "files need"} retry" else ""
    if(!active || progress.filename == null) return count + retry
    val action = when(progress.phase) {
        FileSyncPhase.CHECKING -> "Checking"
        FileSyncPhase.UPLOADING -> "Uploading"
        FileSyncPhase.DOWNLOADING -> "Downloading"
        FileSyncPhase.REMOVING -> "Removing"
    }
    val percent = if(progress.phase == FileSyncPhase.UPLOADING && progress.transferred != null && (progress.size ?: 0) > 0)
        " (${(progress.transferred.coerceIn(0, progress.size!!) * 100 / progress.size)}%)" else ""
    return "$count$retry · $action ${progress.filename}$percent"
}

fun FileSyncProgress.toWorkData(): Data = workDataOf("fileTotal" to total, "fileChecked" to checked, "fileFailed" to failed,
    "filePhase" to phase.name, "fileName" to filename?.take(512), "fileTransferred" to (transferred ?: -1), "fileSize" to (size ?: -1),
    "fileProgressAt" to System.currentTimeMillis())

fun fileSyncProgress(data: Data): FileSyncProgress? {
    val total = data.getInt("fileTotal", 0)
    if(total <= 0) return null
    return FileSyncProgress(total, data.getInt("fileChecked", 0).coerceIn(0,total), data.getInt("fileFailed", 0).coerceIn(0,total),
        FileSyncPhase.entries.find { it.name == data.getString("filePhase") } ?: FileSyncPhase.CHECKING, data.getString("fileName"),
        data.getLong("fileTransferred", -1).takeIf { it >= 0 }, data.getLong("fileSize", -1).takeIf { it >= 0 })
}
