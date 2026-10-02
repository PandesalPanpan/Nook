package app.nook.updates

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun UpdateBanner(
    state: UpdateUiState,
    onLater: () -> Unit,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onInstall: () -> Unit,
    onRetryCheck: () -> Unit
) {
    val info = state.info ?: return
    if (state.dismissed) return
    if (state.status !in setOf(UpdateStatus.AVAILABLE, UpdateStatus.DOWNLOADING, UpdateStatus.READY_TO_INSTALL, UpdateStatus.ERROR)) return
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            when (state.status) {
                UpdateStatus.AVAILABLE -> {
                    Text("Update available", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Nook ${info.versionName}", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (info.releaseNotes.isNotBlank()) {
                        Text("What’s new", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(info.releaseNotes, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("Current: ${state.currentVersion.versionName} · Available: ${info.versionName}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onLater) { Text("Later") }
                        Button(onClick = onDownload) { Text("Update now") }
                    }
                }
                UpdateStatus.DOWNLOADING -> {
                    Text("Downloading Nook ${info.versionName}", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    ProgressContent(state)
                    OutlinedButton(onClick = onCancel) { Text("Cancel download") }
                }
                UpdateStatus.READY_TO_INSTALL -> {
                    Text("Nook ${info.versionName} is ready to install", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    state.message?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Button(onClick = onInstall) { Text("Install update") }
                }
                UpdateStatus.ERROR -> {
                    Text("Update needs attention", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(state.message ?: "The update could not be prepared.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onLater) { Text("Later") }
                        Button(onClick = if (state.failureStage == UpdateFailureStage.DOWNLOAD) onDownload else onRetryCheck) {
                            Text(if (state.failureStage == UpdateFailureStage.DOWNLOAD) "Retry download" else "Check again")
                        }
                    }
                }
                else -> Unit
            }
        }
    }
}

@Composable
internal fun AboutUpdatesCard(
    state: UpdateUiState,
    onCheck: () -> Unit,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onInstall: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Nook", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Version ${state.currentVersion.versionName}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            when (state.status) {
                UpdateStatus.IDLE -> Text("Updates are optional. Nook works offline.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                UpdateStatus.CHECKING -> Text("Checking GitHub Releases…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                UpdateStatus.UP_TO_DATE -> Text("Nook is up to date.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                UpdateStatus.AVAILABLE -> state.info?.let { Text("Nook ${it.versionName} is available.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                UpdateStatus.DOWNLOADING -> {
                    Text("Downloading Nook ${state.info?.versionName.orEmpty()}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    ProgressContent(state)
                    OutlinedButton(onClick = onCancel) { Text("Cancel download") }
                }
                UpdateStatus.READY_TO_INSTALL -> {
                    Text("The verified APK is ready.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    state.message?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Button(onClick = onInstall) { Text("Install update") }
                }
                UpdateStatus.ERROR -> Text(state.message ?: "Could not check for updates.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (state.status == UpdateStatus.AVAILABLE && state.info != null) {
                Button(onClick = onDownload) { Text("Update now") }
            }
            Button(
                onClick = if (state.status == UpdateStatus.ERROR && state.failureStage == UpdateFailureStage.DOWNLOAD) onDownload else onCheck,
                enabled = state.status != UpdateStatus.CHECKING && state.status != UpdateStatus.DOWNLOADING,
                colors = ButtonDefaults.buttonColors()
            ) {
                Text(when {
                    state.status == UpdateStatus.CHECKING -> "Checking…"
                    state.status == UpdateStatus.ERROR && state.failureStage == UpdateFailureStage.DOWNLOAD -> "Retry download"
                    else -> "Check for updates"
                })
            }
        }
    }
}

@Composable
private fun ProgressContent(state: UpdateUiState) {
    val progress = state.progress
    LinearProgressIndicator(
        progress = { progress ?: 0f },
        modifier = Modifier.fillMaxWidth().semantics {
            contentDescription = progress?.let { "Download ${((it * 100).toInt())}% complete" } ?: "Download progress"
        }
    )
    val percent = progress?.let { "${(it * 100).toInt()}%" } ?: "Downloading…"
    Text(percent, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
