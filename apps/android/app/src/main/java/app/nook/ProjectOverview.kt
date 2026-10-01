package app.nook

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.progressSemantics
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.nook.data.Record
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

private fun Record.projectValue(key: String) = data[key]?.jsonPrimitive?.contentOrNull.orEmpty()
private val ProjectInter = FontFamily(Font(R.font.inter))

@Composable internal fun ProjectTaskRow(task: Record, open: () -> Unit, complete: () -> Unit) {
    val done = task.projectValue("completed") == "true"
    val title = task.projectValue("title").ifBlank { "Untitled task" }
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).background(Color(0xff221d1a), RoundedCornerShape(12.dp)), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(48.dp).toggleable(done, role = Role.Checkbox, onValueChange = { complete() })
            .semantics { contentDescription = if(done) "Mark $title incomplete" else "Complete $title" }, contentAlignment = Alignment.Center) {
            Box(Modifier.size(24.dp).background(if(done) Color(0xff3270e6) else Color.Transparent, RoundedCornerShape(7.dp))
                .border(1.dp, if(done) Color(0xff3270e6) else Color(0xff9c9187), RoundedCornerShape(7.dp)), contentAlignment = Alignment.Center) {
                if(done) ProjectText("✓", 14, true)
            }
        }
        Column(Modifier.weight(1f).heightIn(min = 48.dp).clickable(role = Role.Button, onClick = open).padding(vertical = 3.dp, horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            ProjectText(title, 14, true)
            val details = if(done) "Done" else todayActionDetail(task, java.time.LocalDate.now().toString(), false).ifBlank { "Later" }
            ProjectText(details, 11, color = Color(0xffcbbfb4))
        }
    }
}
@Composable private fun ProjectText(text: String, size: Int, bold: Boolean = false, color: Color = Color(0xfff5eee7), modifier: Modifier = Modifier) {
    Text(text, modifier, fontFamily = ProjectInter, fontSize = size.sp, lineHeight = (size * 1.4).sp,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal, color = color)
}

/** Source frame 2:323 outcome and next-action cards, with flowing content and full touch targets. */
@Composable internal fun ProjectOverview(project: Record, records: List<Record>, open: (Record) -> Unit) {
    val area = records.find { it.accountId == project.accountId && it.id == project.projectValue("areaId") && it.kind == "area" && !it.deleted && !it.archived }
    if (area != null) ProjectText("AREA · ${area.projectValue("title").uppercase()}", 10, true, Color(0xffff7e1d))
    Column(Modifier.fillMaxWidth().heightIn(min = 104.dp).background(Color(0xff221d1a), RoundedCornerShape(20.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ProjectText("Outcome", 12, true, Color(0xffcbbfb4))
        ProjectText(project.projectValue("outcome").ifBlank { "Define what finished looks like." }, 15, true)
        val progress = (project.projectValue("progress").toFloatOrNull() ?: 0f).coerceIn(0f, 100f) / 100f
        Box(Modifier.fillMaxWidth().height(8.dp).progressSemantics(progress).background(Color(0xff342d28), RoundedCornerShape(4.dp))) {
            Box(Modifier.fillMaxWidth(progress).fillMaxHeight().background(Color(0xffff7e1d), RoundedCornerShape(4.dp)))
        }
    }
    val next = records.find { it.accountId == project.accountId && it.id == project.projectValue("nextActionId") && it.kind == "task" &&
        !it.deleted && !it.archived && it.projectValue("completed") != "true" }
    Column(Modifier.fillMaxWidth().heightIn(min = 88.dp).background(Color(0xff342d28), RoundedCornerShape(20.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ProjectText("NEXT ACTION", 12, color = Color(0xff171412), modifier = Modifier.background(Color(0xffffce00), RoundedCornerShape(14.dp)).padding(horizontal = 12.dp, vertical = 6.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ProjectText(next?.projectValue("title")?.ifBlank { "Untitled task" } ?: "Choose your next step", 14, true, modifier = Modifier.weight(1f))
            if (next != null) Box(Modifier.heightIn(min = 48.dp).widthIn(min = 58.dp).clickable(role = Role.Button) { open(next) }, contentAlignment = Alignment.Center) {
                ProjectText("Start", 12, color = Color.White, modifier = Modifier.background(Color(0xff3270e6), RoundedCornerShape(14.dp)).padding(horizontal = 12.dp, vertical = 6.dp))
            }
        }
    }
}
