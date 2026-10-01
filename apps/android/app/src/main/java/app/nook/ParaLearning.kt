package app.nook

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.graphics.createBitmap
import app.nook.data.*
import app.nook.data.Record
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private val LearningFont = FontFamily(Font(R.font.inter))
private val LearningYellow = Color(0xffffce00)
private val LearningNight = Color(0xff171412)
private val LearningMuted = Color(0xff9c9187)
private data class LearningTip(val title: String, val detail: String)
private val learningTips = mapOf(
    "inbox" to LearningTip("When unsure, leave it in Inbox.", "Capture first. Choose a useful next step when you’re ready."),
    "project" to LearningTip("Projects have finish lines.", "“Lose 5 kg” = Project · “Stay healthy” = Area"),
    "area" to LearningTip("Areas don’t have finish lines.", "“Stay healthy” = Area · “Lose 5 kg” = Project"),
    "resource" to LearningTip("Keep what interests you.", "Resources hold reference material for topics you care about."),
    "archive" to LearningTip("Archive aggressively. Search is your safety net.", "Inactive things stay searchable. Restore them whenever useful.")
)
@Composable private fun LearningText(value: String, size: Int, bold: Boolean = false, color: Color = MaterialTheme.colorScheme.onSurface) {
    Text(value, fontFamily = LearningFont, fontSize = size.sp, lineHeight = (size * 1.4).sp, fontWeight = if(bold) FontWeight.Bold else FontWeight.Normal, color = color)
}
@Composable private fun LearningPill(value: String) {
    Box(Modifier.background(LearningYellow, RoundedCornerShape(14.dp)).padding(horizontal = 12.dp, vertical = 6.dp)) { LearningText(value, 12, color = LearningNight) }
}
@Composable private fun LearningButton(value: String, primary: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    NookButton(onClick, enabled = enabled, shape = RoundedCornerShape(30.dp), colors = ButtonDefaults.buttonColors(containerColor = if(primary) NookActionBlue else MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.heightIn(min = 48.dp)) { LearningText(value, 12, color = if(primary) Color.White else MaterialTheme.colorScheme.onSurface) }
}
@Composable internal fun ParaTip(repository: NookRepository, records: List<Record>, topic: String) {
    val settings = currentSettings(records)?.let { wireJson.decodeFromJsonElement(AppSettings.serializer(), it.data) } ?: AppSettings()
    val tip = learningTips[topic] ?: return
    if(!settings.tipsEnabled || "para:$topic" in settings.dismissedTips) return
    var guide by rememberSaveable(topic) { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, RoundedCornerShape(24.dp)).padding(horizontal = 18.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        LearningPill("PARA TIP")
        LearningText(tip.title, 16, true)
        LearningText(tip.detail, 12, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            LearningButton("Got it", enabled = !busy) { busy = true; scope.launch { try {
                repository.updateSettings { it.copy(dismissedTips = (it.dismissedTips + "para:$topic").distinct()) }
            } catch(cancelled: CancellationException) { throw cancelled } catch(_: Exception) { error = "Could not save. Try again." } finally { busy = false } } }
            LearningButton("Learn more", true) { guide = true }
        }
        if(error.isNotEmpty()) LearningText(error, 12)
    }
    if(guide) ParaGuide { guide = false }
}
@Composable internal fun ParaGuide(close: () -> Unit) {
    val places = listOf(Triple("PROJECTS", "Outcomes with a finish line", "Finish capstone prototype"), Triple("AREAS", "Responsibilities to maintain", "University · Health"), Triple("RESOURCES", "Topics worth keeping", "ESP32 · Design"), Triple("ARCHIVE", "Inactive things", "Finished projects"))
    Dialog(close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.widthIn(max = 399.dp).fillMaxWidth().heightIn(max = 860.dp).padding(12.dp).background(LearningNight, RoundedCornerShape(24.dp)).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) { LearningMarker(0,24); LearningText("nook", 18, true) }
                LearningButton("Close", onClick = close)
            }
            Spacer(Modifier.height(4.dp))
            LearningText("How Nook organizes", 30, true)
            LearningText("Four places. You don’t need to memorize them.", 12, color = MaterialTheme.colorScheme.onSurfaceVariant)
            places.forEachIndexed { index, (name, description, example) ->
                Row(Modifier.fillMaxWidth().heightIn(min = 100.dp).background(MaterialTheme.colorScheme.surface, RoundedCornerShape(20.dp)).padding(horizontal = 16.dp, vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Box(Modifier.padding(top = 4.dp)) { LearningMarker(index + 1,42) }
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { LearningText(name,11,true,LearningMuted); LearningText(description,13,true); LearningText(example,11,color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth().heightIn(min = 62.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(18.dp)).padding(14.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) { LearningPill("TIP"); LearningText("When unsure, leave it in Inbox.",11) }
            LearningButton("Continue",true,onClick = close)
        }
    }
}
@Composable private fun LearningMarker(index: Int, size: Int) {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val bitmap = remember(index, size, context, density) {
        val svg = com.caverock.androidsvg.SVG.getFromAsset(context.assets,"figma/4-138-imgEllipse${if(index == 0) "" else index}.svg")
        createBitmap((size * density).toInt(), (size * density).toInt()).also { bitmap ->
            val canvas = android.graphics.Canvas(bitmap); canvas.scale(density,density); canvas.drawPicture(svg.renderToPicture())
        }
    }
    Image(bitmap.asImageBitmap(), null, Modifier.size(size.dp))
}
@Composable internal fun ParaPreferences(repository: NookRepository) {
    val records by repository.observeAll().collectAsStateWithLifecycle(initialValue = emptyList())
    val settings = currentSettings(records)?.let { wireJson.decodeFromJsonElement(AppSettings.serializer(),it.data) } ?: AppSettings()
    var guide by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    fun save(change: (AppSettings) -> AppSettings) { busy = true; scope.launch { try { repository.updateSettings(change) } catch(cancelled: CancellationException) { throw cancelled } catch(_: Exception) { error = "Could not save learning preferences." } finally { busy = false } } }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        LearningText("Learn PARA", 24, true)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(settings.tipsEnabled, enabled = !busy, onCheckedChange = { enabled -> save { it.copy(tipsEnabled = enabled) } }, modifier = Modifier.semantics { contentDescription = "Show contextual PARA tips" })
            LearningText("Show contextual PARA tips", 14)
        }
        LearningButton("How Nook organizes") { guide = true }
        LearningButton("Reset dismissed tips", enabled = !busy && settings.dismissedTips.any { it.startsWith("para:") }) { save { it.copy(dismissedTips = it.dismissedTips.filterNot { id -> id.startsWith("para:") }) } }
        if(error.isNotEmpty()) LearningText(error, 12)
    }
    if(guide) ParaGuide { guide = false }
}
