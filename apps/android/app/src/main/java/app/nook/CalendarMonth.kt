package app.nook

import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.core.graphics.createBitmap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter

@Composable private fun CalendarMarker(asset: Int, size: Int, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val bitmap = remember(context, density, asset, size) {
        val svg = com.caverock.androidsvg.SVG.getFromAsset(context.assets, "figma/2-596-imgEllipse$asset.svg")
        createBitmap((size * density).toInt(), (size * density).toInt()).also {
            val canvas = android.graphics.Canvas(it); canvas.scale(density, density); canvas.drawPicture(svg.renderToPicture())
        }
    }
    Image(bitmap.asImageBitmap(), null, modifier.size(size.dp))
}

@Composable internal fun CalendarMonth(selected: LocalDate, scheduled: Set<LocalDate>, choose: (LocalDate) -> Unit) {
    val font = FontFamily(Font(R.font.inter))
    val month = YearMonth.from(selected)
    val first = month.atDay(1)
    val start = first.minusDays((first.dayOfWeek.value % 7).toLong())
    val weeks = ((first.dayOfWeek.value % 7 + month.lengthOfMonth() + 6) / 7)
    BoxWithConstraints(Modifier.fillMaxWidth().background(Color(0xff221d1a), RoundedCornerShape(20.dp))) {
        val sidePadding = ((maxWidth - 336.dp) / 2).coerceIn(0.dp, 10.dp)
        Column(Modifier.fillMaxWidth().padding(horizontal = sidePadding, vertical = 12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { val previous = month.minusMonths(1); choose(previous.atDay(minOf(selected.dayOfMonth, previous.lengthOfMonth()))) }, enabled = month.year > 0 || month.monthValue > 1, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).semantics { contentDescription = "Previous month" }) { Text("‹", fontSize = 24.sp) }
            Text(month.format(DateTimeFormatter.ofPattern("MMMM uuuu")), modifier = Modifier.weight(1f), textAlign = TextAlign.Center, color = Color(0xfff5eee7), fontFamily = font, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Bold)
            TextButton(onClick = { val next = month.plusMonths(1); choose(next.atDay(minOf(selected.dayOfMonth, next.lengthOfMonth()))) }, enabled = month.year < 9999 || month.monthValue < 12, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).semantics { contentDescription = "Next month" }) { Text("›", fontSize = 24.sp) }
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val gridWidth = maxOf(maxWidth, 336.dp)
            Column(Modifier.horizontalScroll(rememberScrollState()).width(gridWidth)) {
        Row { listOf("S", "M", "T", "W", "T", "F", "S").forEach { Text(it, Modifier.weight(1f).wrapContentWidth(), fontFamily = font, fontSize = 10.sp, lineHeight = 18.sp, fontWeight = FontWeight.Bold, color = Color(0xff9c9187)) } }
        repeat(weeks) { week -> Row(Modifier.fillMaxWidth()) {
            repeat(7) { column ->
                val day = start.plusDays((week * 7 + column).toLong())
                Box(Modifier.weight(1f).heightIn(min = 48.dp).selectable(day == selected, enabled = day.year in 0..9999, role = Role.Button, onClick = { choose(day) }).semantics { contentDescription = "Choose $day${if(day in scheduled) ", scheduled work" else ""}" }, contentAlignment = Alignment.Center) {
                    Box(Modifier.size(34.dp), contentAlignment = Alignment.Center) {
                        if(day == selected) CalendarMarker(1, 34)
                        Text(day.dayOfMonth.toString(), fontFamily = font, fontSize = 12.sp, lineHeight = 18.sp, fontWeight = if(day == selected) FontWeight.Bold else FontWeight.Medium, color = if(day == selected) Color.White else if(YearMonth.from(day) == month) Color(0xfff5eee7) else Color(0xff9c9187))
                    }
                    if(day in scheduled) CalendarMarker(2, 5, Modifier.align(Alignment.BottomCenter).padding(bottom = 3.dp))
                }
            }
        } }
            }
        }
        }
    }
}
