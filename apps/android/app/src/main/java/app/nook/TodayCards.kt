package app.nook

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.nook.data.Record
import androidx.core.graphics.createBitmap
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val TodayInter = FontFamily(Font(R.font.inter))
private val TodayText = Color(0xfff5eee7)
private val TodaySecondary = Color(0xffcbbfb4)
private fun Record.todayValue(key: String) = data[key]?.jsonPrimitive?.contentOrNull.orEmpty()
private fun Record.todayTitle() = todayValue("title").ifBlank { "Untitled task" }

internal fun todayActions(tasks: List<Record>, projects: List<Record>, today: String): List<Record> {
    val nextActions = projects.filter { it.kind == "project" && !it.archived && !it.deleted }
        .map { it.accountId to it.todayValue("nextActionId") }.toSet()
    return tasks.filter { task -> task.kind == "task" && !task.deleted && !task.archived && task.todayValue("completed") != "true" &&
        ((task.accountId to task.id) in nextActions || listOf("doDate", "deadline").any { key -> task.todayValue(key).let { it.isNotEmpty() && it <= today } }) }
}

internal fun todayActionDetail(task: Record, today: String, nextAction: Boolean): String {
    val doDate = task.todayValue("doDate")
    val deadline = task.todayValue("deadline")
    return listOfNotNull(
        doDate.takeIf { it.isNotEmpty() }?.let { if (it == today) "Do today" else "Do $it" },
        deadline.takeIf { it.isNotEmpty() }?.let { "Due $it" },
        "Project next action".takeIf { nextAction },
    ).joinToString(" · ")
}

@Composable private fun TodayLabel(text: String, size: Int, modifier: Modifier = Modifier, bold: Boolean = false, color: Color = TodayText) {
    Text(text, modifier, fontFamily = TodayInter, fontSize = size.sp, lineHeight = (size * 1.4).sp,
        fontWeight = if(bold) FontWeight.Bold else FontWeight.Normal, color = color)
}

/** Flow layout preserves the source card dimensions while allowing large text and longer real records. */
@Composable internal fun TodayCards(tasks: List<Record>, today: String, captureCount: Int, open: (Record) -> Unit, complete: (Record) -> Unit, inbox: () -> Unit, projects: List<Record> = emptyList()) {
    val nextActions = projects.filter { it.kind == "project" && !it.archived && !it.deleted }
        .map { it.accountId to it.todayValue("nextActionId") }.toSet()
    val available = tasks.filter { it.kind == "task" && !it.deleted && !it.archived && it.todayValue("completed") != "true" }
    val due = todayActions(tasks, projects, today)
    Column(Modifier.fillMaxWidth().background(Color(0xff221d1a), RoundedCornerShape(20.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        TodayLabel("TODAY", 12, color = Color(0xff171412), modifier = Modifier.background(Color(0xffffce00), RoundedCornerShape(14.dp)).padding(horizontal = 12.dp, vertical = 6.dp))
        if(due.isEmpty()) TodayLabel("No scheduled actions. Capture a thought when it comes to mind.", 13, color = TodaySecondary, modifier = Modifier.padding(top = 10.dp))
        due.forEach { task ->
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(48.dp).toggleable(value = false, role = Role.Checkbox, onValueChange = { complete(task) })
                    .semantics { contentDescription = "Complete ${task.todayTitle()}" }, contentAlignment = Alignment.Center) {
                    Box(Modifier.size(24.dp).border(1.dp, Color(0xff9c9187), RoundedCornerShape(7.dp)))
                }
                Column(Modifier.weight(1f).heightIn(min = 48.dp).clickable(role = Role.Button) { open(task) }.padding(vertical = 3.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    TodayLabel(task.todayTitle(), 14, bold = true)
                    TodayLabel(todayActionDetail(task, today, (task.accountId to task.id) in nextActions), 11, color = TodaySecondary)
                }
            }
        }
    }
    Column(Modifier.fillMaxWidth().heightIn(min = 118.dp).background(Color(0xff221d1a), RoundedCornerShape(20.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TodayLabel("Coming up", 14, bold = true)
        val upcoming = available.filter { it.todayValue("deadline") > today }.sortedBy { it.todayValue("deadline") }.take(3)
        if(upcoming.isEmpty()) TodayLabel("No upcoming deadlines.", 12, color = TodaySecondary)
        upcoming.forEachIndexed { index, task ->
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button) { open(task) }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TodayLabel(LocalDate.parse(task.todayValue("deadline")).format(DateTimeFormatter.ofPattern("MMM d")), 12, bold = true,
                    color = if(index % 2 == 0) Color(0xffff7e1d) else Color(0xff3fae9b), modifier = Modifier.width(60.dp))
                TodayLabel(task.todayTitle(), 13, modifier = Modifier.weight(1f))
            }
        }
    }
    BoxWithConstraints(Modifier.fillMaxWidth().heightIn(min = 82.dp).background(Color(0xff342d28), RoundedCornerShape(20.dp)).padding(16.dp)) {
        val scale = LocalDensity.current.fontScale
        // Reserve room for readable summary text as well as the icon and scaled action.
        val minimumRowWidth = (38 + 28 + maxOf(70f, 42 * scale + 24) + 100 * scale).dp
        if (maxWidth < minimumRowWidth) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TodayInboxSummary(captureCount, Modifier.fillMaxWidth())
                TodayProcess(inbox, Modifier.align(Alignment.End))
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                TodayInboxSummary(captureCount, Modifier.weight(1f))
                TodayProcess(inbox)
            }
        }
    }
}

@Composable private fun TodayInboxSummary(captureCount: Int, modifier: Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        val context = LocalContext.current
        val density = LocalDensity.current.density
        val marker = remember(context, density) {
            val svg = com.caverock.androidsvg.SVG.getFromAsset(context.assets, "figma/2-121-imgEllipse1.svg")
            createBitmap((38 * density).toInt(), (38 * density).toInt()).also {
                val canvas = android.graphics.Canvas(it); canvas.scale(density, density); canvas.drawPicture(svg.renderToPicture())
            }
        }
        Image(marker.asImageBitmap(), null, Modifier.size(38.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            TodayLabel("Inbox · $captureCount", 15, bold = true)
            TodayLabel(if(captureCount == 0) "A little room to think." else "A few thoughts are waiting.", 12, color = TodaySecondary)
        }
    }
}

@Composable private fun TodayProcess(inbox: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.heightIn(min = 48.dp).widthIn(min = 70.dp).clickable(role = Role.Button, onClick = inbox), contentAlignment = Alignment.Center) {
        TodayLabel("Process", 12, color = Color.White, modifier = Modifier.background(Color(0xff3270e6), RoundedCornerShape(14.dp)).padding(horizontal = 12.dp, vertical = 6.dp))
    }
}
