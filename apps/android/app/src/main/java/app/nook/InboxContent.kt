package app.nook

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.createBitmap
import app.nook.data.NookRepository
import app.nook.data.Record
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

private val InboxInter = FontFamily(Font(R.font.inter))
@Composable private fun InboxMarker(asset: Int, size: Int) {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val bitmap = remember(context, density, asset, size) {
        val svg = com.caverock.androidsvg.SVG.getFromAsset(context.assets, "figma/2-166-imgEllipse${if(asset == 0) "" else asset}.svg")
        createBitmap((size * density).toInt(), (size * density).toInt()).also {
            val canvas = android.graphics.Canvas(it); canvas.scale(density, density); canvas.drawPicture(svg.renderToPicture())
        }
    }
    Image(bitmap.asImageBitmap(), null, Modifier.size(size.dp))
}

@Composable internal fun InboxContent(repository: NookRepository, captures: List<Record>, open: (Record) -> Unit, saved: () -> Unit) {
    val scope = rememberCoroutineScope()
    var draft by rememberSaveable { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val editor: @Composable (Modifier) -> Unit = { modifier ->
        BasicTextField(draft, { draft = it }, enabled = !saving, modifier = modifier.heightIn(min = 48.dp).semantics { contentDescription = "Inbox thought" },
            textStyle = TextStyle(fontFamily = InboxInter, fontSize = 15.sp, lineHeight = 21.sp, color = Color(0xfff5eee7)), cursorBrush = SolidColor(Color(0xfff5eee7)),
            decorationBox = { input -> Box(contentAlignment = Alignment.CenterStart) {
                if(draft.isEmpty()) Text("Write anything…", fontFamily = InboxInter, fontSize = 15.sp, color = Color(0xff9c9187))
                input()
            } })
    }
    val saveAction: @Composable (Modifier) -> Unit = { modifier ->
        TextButton(onClick = {
            saving = true; error = ""
            scope.launch {
                try { repository.capture(draft); draft = ""; saved() }
                catch(cancelled: CancellationException) { throw cancelled }
                catch(_: Exception) { error = "Could not save. Your thought is still here." }
                finally { saving = false }
            }
        }, enabled = !saving && draft.isNotBlank(), modifier = modifier.heightIn(min = 48.dp), contentPadding = PaddingValues(horizontal = 12.dp)) {
            Text(if(saving) "Saving…" else "Save", fontFamily = InboxInter, fontSize = 12.sp, color = if(draft.isBlank()) Color(0xff9c9187) else Color.White,
                modifier = Modifier.background(if(draft.isBlank()) Color(0xff342d28) else Color(0xff3270e6), RoundedCornerShape(14.dp)).padding(horizontal = 12.dp, vertical = 6.dp))
        }
    }
    BoxWithConstraints(Modifier.fillMaxWidth().heightIn(min = 52.dp).background(Color(0xff221d1a), RoundedCornerShape(20.dp))
        .border(1.dp, Color(0xff453d37), RoundedCornerShape(20.dp)).padding(horizontal = 14.dp)) {
        val scale = LocalDensity.current.fontScale
        // Keep the editor wide enough to read while leaving space for the scaled Save label.
        val minimumRowWidth = (24 + 28 + maxOf(88f, 30 * scale + 48) + 100 * scale).dp
        if (maxWidth < minimumRowWidth) {
            Column(Modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    InboxMarker(0, 24)
                    editor(Modifier.weight(1f))
                }
                saveAction(Modifier.align(Alignment.End))
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                InboxMarker(0, 24)
                editor(Modifier.weight(1f))
                saveAction(Modifier)
            }
        }
    }
    if(error.isNotEmpty()) Text(error, color = Color(0xffef6a67), fontFamily = InboxInter, fontSize = 12.sp)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        captures.forEachIndexed { index, record ->
            val type = record.data["captureType"]?.jsonPrimitive?.contentOrNull.orEmpty()
            Row(Modifier.fillMaxWidth().heightIn(min = 70.dp).background(Color(0xff221d1a), RoundedCornerShape(18.dp)).clickable(role = Role.Button) { open(record) }.padding(horizontal = 14.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                InboxMarker(when(type) { "task" -> 2; "image" -> 3; "link" -> 4; else -> if(captures.take(index).count { it.data["captureType"]?.jsonPrimitive?.contentOrNull == "text" } % 2 == 1) 5 else 1 }, 36)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(when(type) { "task" -> "TASK"; "image" -> "PHOTO"; "link" -> "LINK"; else -> "THOUGHT" }, fontFamily = InboxInter, fontSize = 10.sp, lineHeight = 18.sp, fontWeight = FontWeight.Bold, color = Color(0xff9c9187))
                    Text(record.data["body"]?.jsonPrimitive?.contentOrNull?.ifBlank { "Photo capture" } ?: "Capture", fontFamily = InboxInter, fontSize = 13.sp, lineHeight = 18.2.sp, fontWeight = FontWeight.Medium, color = Color(0xfff5eee7), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                val minutes = ((System.currentTimeMillis() - record.createdAt).coerceAtLeast(0) / 60_000)
                Text(when { minutes < 1 -> "Now"; minutes < 60 -> "${minutes}m"; minutes < 1440 -> "${minutes / 60}h"; minutes < 2880 -> "Yesterday"; else -> "${minutes / 1440}d" },
                    modifier = Modifier.align(Alignment.Top), fontFamily = InboxInter, fontSize = 10.sp, lineHeight = 18.sp, color = Color(0xff9c9187))
            }
        }
        if(captures.isEmpty()) Text("Your Inbox is clear. Nothing to catch up on.", fontFamily = InboxInter, fontSize = 14.sp, color = Color(0xffcbbfb4))
    }
}
