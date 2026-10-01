package app.nook

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.createBitmap
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.nook.data.Record
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull

@Composable internal fun AreaProjectRow(title: String, index: Int, open: () -> Unit) {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val bitmap = remember(index,context,density) {
        val svg = com.caverock.androidsvg.SVG.getFromAsset(context.assets,"figma/2-432-imgEllipse${index % 3 + 1}.svg")
        createBitmap((32 * density).toInt(),(32 * density).toInt()).also { bitmap -> val canvas = android.graphics.Canvas(bitmap);canvas.scale(density,density);canvas.drawPicture(svg.renderToPicture()) }
    }
    Surface(onClick = open,shape = RoundedCornerShape(16.dp),color = MaterialTheme.colorScheme.surface,modifier = Modifier.fillMaxWidth().heightIn(min = 58.dp)) {
        Row(Modifier.padding(horizontal = 14.dp,vertical = 13.dp),horizontalArrangement = Arrangement.spacedBy(12.dp),verticalAlignment = Alignment.CenterVertically) {
            Image(bitmap.asImageBitmap(),null,Modifier.size(32.dp))
            Text(title,fontFamily = FontFamily(Font(R.font.inter)),fontWeight = FontWeight.Bold,fontSize = 13.sp,lineHeight = 18.sp)
        }
    }
}

@Composable internal fun RecordContextPicker(label: String, selectedId: String, options: List<Record>, change: (String) -> Unit) {
    var choosing by remember { mutableStateOf(false) }
    fun title(record: Record) = record.data["title"]?.jsonPrimitive?.contentOrNull.orEmpty().ifBlank { "Untitled ${record.kind}" }
    val selected = options.find { it.id == selectedId }
    OutlinedButton(onClick = { choosing = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Text("$label · ${selected?.let(::title) ?: if(selectedId.isEmpty()) "None" else "Unavailable item"}", fontFamily = FontFamily(Font(R.font.inter)))
    }
    if(choosing) AlertDialog(onDismissRequest = { choosing = false }, title = { Text("Choose $label") }, text = {
        Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
            TextButton(onClick = { change(""); choosing = false }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("No ${label.lowercase()}") }
            options.forEach { record -> TextButton(onClick = { change(record.id); choosing = false }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(title(record)) } }
        }
    }, confirmButton = { TextButton(onClick = { choosing = false }) { Text("Cancel") } })
}
