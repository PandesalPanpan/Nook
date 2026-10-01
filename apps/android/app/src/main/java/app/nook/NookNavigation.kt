package app.nook

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.createBitmap

@Composable internal fun NookNavigation(page: String, navigate: (String) -> Unit) {
    val destinations = listOf("Today", "Inbox", "Projects", "Library")
    val current = if(page in destinations.take(3)) page else "Library"
    Column(Modifier.fillMaxWidth().background(Color(0xff221d1a)).navigationBarsPadding()) {
        HorizontalDivider(color = Color(0xff453d37))
        Row(Modifier.fillMaxWidth()) {
            destinations.forEach { destination ->
                val selected = destination == current
                Column(Modifier.weight(1f).heightIn(min = 60.dp).selectable(selected, role = Role.Tab, onClick = { navigate(destination) }).padding(top = 10.dp, bottom = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val context = LocalContext.current
                    val density = LocalDensity.current.density
                    val marker = remember(context, density, selected) {
                        val svg = com.caverock.androidsvg.SVG.getFromAsset(context.assets, "figma/2-121-imgEllipse${if(selected) 2 else 3}.svg")
                        createBitmap((18 * density).toInt(), (18 * density).toInt()).also {
                            val canvas = android.graphics.Canvas(it); canvas.scale(density, density); canvas.drawPicture(svg.renderToPicture())
                        }
                    }
                    Image(marker.asImageBitmap(), null, Modifier.size(18.dp))
                    Text(destination, fontFamily = FontFamily(Font(R.font.inter)), fontSize = 10.sp, lineHeight = 18.sp,
                        fontWeight = if(selected) FontWeight.Bold else FontWeight.Medium, color = if(selected) Color(0xfff5eee7) else Color(0xff9c9187))
                }
            }
        }
    }
}
