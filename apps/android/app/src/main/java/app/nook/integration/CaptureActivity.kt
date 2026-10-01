package app.nook.integration

import app.nook.NookButton

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import app.nook.AppGraph
import app.nook.NookTheme
import app.nook.data.OriginalInput
import app.nook.data.NookRepository
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class CaptureRequest(val body: String, val type: String, val uris: List<Uri>)
@Suppress("DEPRECATION")
fun captureRequest(intent: Intent): CaptureRequest {
    val body = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty()
    val streams: List<Uri> = when(intent.action) {
        Intent.ACTION_SEND -> listOfNotNull(if(Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) else intent.getParcelableExtra(Intent.EXTRA_STREAM))
        Intent.ACTION_SEND_MULTIPLE -> (if(Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java) else intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)).orEmpty()
        else -> emptyList()
    }
    val uris = (streams + (0 until (intent.clipData?.itemCount ?: 0)).mapNotNull { intent.clipData?.getItemAt(it)?.uri }).distinct()
    val type = when { uris.isNotEmpty() -> "image"; intent.getStringExtra("captureType") == "task" -> "task"; body.trim().let { it.startsWith("https://") || it.startsWith("http://") } -> "link"; intent.getStringExtra("captureType") == "link" -> "link"; else -> "text" }
    return CaptureRequest(body, type, uris)
}

suspend fun readOriginals(context: Context, uris: List<Uri>): List<OriginalInput> = withContext(Dispatchers.IO) {
    require(uris.size <= 20) { "Choose at most 20 attachments" }
    var totalBytes = 0L
    uris.map { uri ->
        require(uri.scheme == "content") { "Attachment URI unavailable" }
        val filename = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor -> if(cursor.moveToFirst()) cursor.getString(0) else "attachment" } ?: "attachment"
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            val output = ByteArrayOutputStream(); val buffer = ByteArray(8192)
            while(true) { val count = input.read(buffer); if(count < 0) break; totalBytes += count; require(totalBytes <= 50 * 1024 * 1024) { "Capture attachments exceed 50 MB in total" }; output.write(buffer, 0, count) }
            output.toByteArray()
        } ?: error("Attachment unavailable")
        OriginalInput(filename, context.contentResolver.getType(uri) ?: "application/octet-stream", bytes)
    }
}

/** Shared by ACTION_SEND, launcher shortcuts and widget capture buttons. */
@OptIn(ExperimentalLayoutApi::class)
class CaptureActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
        window.setGravity(android.view.Gravity.BOTTOM)
        val request = captureRequest(intent)
        val cameraFirst = intent.getStringExtra("captureType") == "photo"
        setContent { NookTheme {
            val repository by produceState<NookRepository?>(null) { value = AppGraph.activeRepository(applicationContext) }
            val scope = rememberCoroutineScope(); val focus = remember { FocusRequester() }
            var body by rememberSaveable { mutableStateOf(request.body) }
            var type by rememberSaveable { mutableStateOf(request.type) }
            var uris by rememberSaveable { mutableStateOf(ArrayList(request.uris)) }
            var cameraUri by rememberSaveable { mutableStateOf<Uri?>(null) }
            var saving by remember { mutableStateOf(false) }; var error by remember { mutableStateOf("") }
            val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { selected -> uris = ArrayList(selected.take(20)); if(uris.isNotEmpty()) type = "image" }
            val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success -> if(success) cameraUri?.let { uris = arrayListOf(it); type = "image" } }
            fun takePhoto() {
                val folder = File(cacheDir, "camera").apply { mkdirs() }
                cameraUri = FileProvider.getUriForFile(this, "$packageName.files", File.createTempFile("nook-", ".jpg", folder))
                try { camera.launch(cameraUri!!) } catch (_: android.content.ActivityNotFoundException) { error = "No camera app is available. Choose Photo instead." }
            }
            LaunchedEffect(Unit) { if(cameraFirst && cameraUri == null) takePhoto() else focus.requestFocus() }
            CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
            Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)).imePadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if(intent.action == Intent.ACTION_SEND || intent.action == Intent.ACTION_SEND_MULTIPLE) "Save to Nook" else "Quick capture", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                OutlinedTextField(body, { body = it }, label = { Text("Thought") }, placeholder = { Text(if(uris.isEmpty()) "What’s on your mind?" else "Add a note… optional") }, maxLines = 4, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp).focusRequester(focus))
                Text("Saved to Inbox by default", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(type == "task", { type = if(type == "task") "text" else "task" }, label = { Text("Task") })
                    FilterChip(type == "link", { type = if(type == "link") "text" else "link" }, label = { Text("Link") })
                    TextButton(onClick = { photoPicker.launch("image/*") }) { Text("Photo") }
                    TextButton(onClick = ::takePhoto) { Text("Camera") }
                }
                if(uris.isNotEmpty()) Text("${uris.size} original ${if(uris.size == 1) "file" else "files"} attached", fontSize = 12.sp)
                if(error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton(onClick = ::finish, enabled = !saving) { Text("Cancel") }
                    NookButton(onClick = { saving = true; scope.launch { try {
                        val destination = requireNotNull(repository) { "Your Nook is still opening" }
                        check(AppGraph.repository(applicationContext).accountId == destination.accountId) { "The active account changed. Reopen capture to save." }
                        val originals = readOriginals(this@CaptureActivity, uris)
                        check(AppGraph.repository(applicationContext).accountId == destination.accountId) { "The active account changed. Reopen capture to save." }
                        destination.capture(body, if(uris.isNotEmpty() && type == "text") "image" else type, originals)
                        scheduleSystemRefresh(applicationContext)
                        android.widget.Toast.makeText(this@CaptureActivity, "Saved to Inbox", android.widget.Toast.LENGTH_SHORT).show()
                        setResult(RESULT_OK); finish()
                    } catch(exception: Exception) { error = exception.message ?: "Could not save. Please try again."; saving = false } } }, enabled = repository != null && !saving && (body.isNotBlank() || uris.isNotEmpty()), modifier = Modifier.weight(1f), shape = CircleShape) { Text(if(saving) "Saving…" else "Save") }
                }
            }
            }
        } }
    }
}
