package app.nook.integration

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.glance.*
import androidx.glance.action.*
import androidx.glance.appwidget.*
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.layout.*
import androidx.glance.unit.ColorProvider
import androidx.work.*
import app.nook.AppGraph
import app.nook.MainActivity
import app.nook.R
import app.nook.todayActions
import app.nook.todayActionDetail
import app.nook.data.Record
import app.nook.data.decode
import kotlinx.serialization.json.*
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlin.math.max
import kotlin.math.floor

private val Light = Color(0xfff5eee7)
private val Secondary = Color(0xffcbbfb4)
private val Blue = Color(0xff3270e6)
private val Orange = Color(0xffff7e1d)
private val Warm = Color(0xff342d28)
private val TaskId = ActionParameters.Key<String>("taskId")
private val AccountId = ActionParameters.Key<String>("accountId")
private fun Record.value(key: String) = data[key]?.jsonPrimitive?.contentOrNull.orEmpty()
private fun Record.title() = value("title").ifBlank { value("body").take(80).ifBlank { "Photo capture" } }
private fun capture(context: Context, type: String = "text") = actionStartActivity(Intent(context, CaptureActivity::class.java).putExtra("captureType", type))
private fun open(context: Context, page: String = "Today", id: String? = null) = actionStartActivity(Intent(context, MainActivity::class.java).putExtra("page", page).putExtra("recordId", id))

abstract class NookWidget(private val kind: String) : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val repository = AppGraph.activeRepository(context)
        val records = repository.db.records().byKinds(repository.accountId, listOf("capture", "task", "project")).map { it.decode() }.filter { !it.deleted && !it.archived }
        val widgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        val projectId = context.getSharedPreferences("nook-widgets", Context.MODE_PRIVATE).getString("${repository.accountId}:$widgetId", null)
        val brand = if(kind == "quick") com.caverock.androidsvg.SVG.getFromAsset(context.assets, "figma/3-47-imgEllipse.svg").let { svg ->
            val pixels = (36 * context.resources.displayMetrics.density).toInt()
            android.graphics.Bitmap.createBitmap(pixels, pixels, android.graphics.Bitmap.Config.ARGB_8888).also {
                val canvas = android.graphics.Canvas(it); canvas.scale(context.resources.displayMetrics.density, context.resources.displayMetrics.density); canvas.drawPicture(svg.renderToPicture())
            }
        } else null
        provideContent {
            val size = LocalSize.current
            val captures = records.filter { it.kind == "capture" }.sortedByDescending { it.createdAt }
            val tasks = records.filter { it.kind == "task" && it.value("completed") != "true" }
            val today = LocalDate.now().toString()
            val projects = records.filter { it.kind == "project" }
            val due = todayActions(tasks, projects, today)
            val nextActions = projects.map { it.accountId to it.value("nextActionId") }.toSet()
            val compact = size.height < 110.dp
            val largeText = context.resources.configuration.fontScale > 1.3f
            val quickGrid = kind == "quick" && (compact || largeText)
            val padding = if(quickGrid) 0 else if(kind == "capture") 8 else if(kind == "project" && size.height < 210.dp) 12 else 16
            val root = GlanceModifier.fillMaxSize().background(ImageProvider(if(kind == "capture") R.drawable.widget_capture else R.drawable.widget_surface)).cornerRadius(28.dp).padding(padding.dp)
            when(kind) {
                "capture" -> Column(root.clickable(capture(context)), horizontalAlignment = Alignment.Horizontal.CenterHorizontally, verticalAlignment = Alignment.Vertical.CenterVertically) {
                    val plusSize = if(largeText && size.height < 88.dp) 20 else if(size.height < 88.dp) 28 else 36
                    Label("+", plusSize, color = Color(0xff171412), description = "Quick capture")
                    if(size.height.value >= widgetTextHeight(context, plusSize) + widgetTextHeight(context, 12) + 16) Label("Capture", 12, true, color = Color(0xff171412))
                }
                "quick" -> Column(root, verticalAlignment = Alignment.Vertical.CenterVertically) {
                    if(quickGrid) {
                        listOf(listOf("Text" to "text", "Task" to "task"), listOf("Photo" to "photo", "Link" to "link")).forEach { entries ->
                            Row(GlanceModifier.fillMaxWidth().defaultWeight()) { entries.forEach { (label, type) ->
                                WidgetButton(label, capture(context, type), GlanceModifier.defaultWeight(), fontSize = 12, height = max(44, (size.height.value / 2).toInt()))
                            } }
                        }
                    } else {
                    Row(GlanceModifier.fillMaxWidth().height(44.dp).clickable(capture(context)), verticalAlignment = Alignment.Vertical.CenterVertically) {
                        if(brand != null) Image(ImageProvider(brand), contentDescription = null, modifier = GlanceModifier.size(36.dp))
                        Spacer(GlanceModifier.width(if(size.width < 240.dp) 8.dp else 12.dp)); Column { Label("What’s on your mind?", if(size.width < 240.dp) 12 else 14, true); if(size.height >= 110.dp) Label("Tap to capture immediately", 11, color = Secondary) }
                    }
                    Spacer(GlanceModifier.height(if(compact) 4.dp else 8.dp)); Row(GlanceModifier.fillMaxWidth()) { listOf("Text" to "text", "Task" to "task", "Photo" to "photo", "Link" to "link").forEach { (label, type) ->
                        WidgetButton(label, capture(context, type), GlanceModifier.defaultWeight(), fontSize = 11, height = if(compact) 32 else 40); Spacer(GlanceModifier.width(4.dp))
                    } }
                    }
                }
                "inbox" -> Column(root) {
                    val badgeHeight = max(28f, widgetTextHeight(context, 12) + 8)
                    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Vertical.CenterVertically) { Label("Inbox", 18, true, GlanceModifier.defaultWeight()); Label(captures.size.toString(), 12, modifier = GlanceModifier.height(badgeHeight.dp).background(ImageProvider(R.drawable.widget_pill_orange)).padding(horizontal = 12.dp, vertical = 4.dp), color = Color(0xff171412)) }
                    Spacer(GlanceModifier.height(8.dp))
                    val rowHeight = max(44f, widgetTextHeight(context, 12))
                    val rows = floor((size.height.value - padding * 2 - max(widgetTextHeight(context, 18), badgeHeight) - 8 - max(44f, widgetTextHeight(context, 11) + 8)) / rowHeight).toInt().coerceIn(0, if(size.height < 200.dp) 2 else 3)
                    captures.take(rows).forEach { record -> Label(record.title(), 12, true, GlanceModifier.fillMaxWidth().height(rowHeight.dp).clickable(open(context, "Inbox", record.id))) }
                    if(captures.isEmpty()) Label("A little room to think.", 12, color = Secondary)
                    Spacer(GlanceModifier.defaultWeight())
                    Row(GlanceModifier.fillMaxWidth()) {
                        WidgetButton(if(size.width < 220.dp || largeText) "Process" else "Process inbox", open(context, "Inbox"), GlanceModifier.defaultWeight(), Blue, 11, height = if(size.width < 220.dp) 32 else 44)
                        Spacer(GlanceModifier.width(6.dp))
                        if(size.width < 220.dp) WidgetButton("+", capture(context), GlanceModifier.width(44.dp), fontSize = 12, height = 32, description = "Quick capture")
                        else WidgetButton(if(largeText) "Capture" else "+ Capture", capture(context), GlanceModifier.defaultWeight(), fontSize = 11)
                    }
                }
                "today" -> Column(root) {
                    val headingHeight = max(44f, widgetTextHeight(context, 18))
                    Label("Today", 18, true, GlanceModifier.height(headingHeight.dp).clickable(open(context))); Label("${due.size} ${if(due.size == 1) "thing" else "things"} worth doing", 11, color = Secondary)
                    Spacer(GlanceModifier.height(8.dp))
                    val rowHeight = max(44f, widgetTextHeight(context, 13) + widgetTextHeight(context, 11))
                    val rows = floor((size.height.value - padding * 2 - headingHeight - widgetTextHeight(context, 11) - 8) / rowHeight).toInt().coerceIn(0, if(size.height < 200.dp) 2 else 3)
                    due.take(rows).forEach { task -> Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Vertical.CenterVertically) {
                        CheckBox(false, actionRunCallback<CompleteTaskAction>(actionParametersOf(TaskId to task.id, AccountId to repository.accountId)), modifier = GlanceModifier.size(44.dp), colors = CheckboxDefaults.colors(checkedColor = ColorProvider(Blue), uncheckedColor = ColorProvider(Color(0xff9c9187))))
                        Column(GlanceModifier.defaultWeight().height(rowHeight.dp).clickable(open(context, "Projects", task.id))) { Label(task.title(), 13, true); Label(todayActionDetail(task, today, (task.accountId to task.id) in nextActions), 11, color = Secondary) }
                    } }
                    if(due.isEmpty()) Label("No scheduled actions. Capture when ready.", 12, color = Secondary)
                }
                "project" -> Column(root) {
                    val project = records.find { it.kind == "project" && it.id == projectId }
                    val configure = actionStartActivity(Intent(context, ProjectWidgetConfigurationActivity::class.java).putExtra(android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
                    if(project == null) { Label("Project", 18, true); Spacer(GlanceModifier.height(12.dp)); WidgetButton("Choose a project", configure) }
                    else {
                        if(largeText || size.height < 210.dp) {
                            Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Vertical.CenterVertically) {
                                Label(project.title(), 18, true, GlanceModifier.defaultWeight().height(max(44f, widgetTextHeight(context, 18)).dp).clickable(open(context, "Projects", project.id)))
                                WidgetButton("…", configure, GlanceModifier.width(44.dp), description = "Choose project")
                            }
                        } else {
                            Box(GlanceModifier.height(44.dp).clickable(configure), contentAlignment = Alignment.Center) {
                                Label("PROJECT", 11, true, GlanceModifier.height(28.dp).background(ImageProvider(R.drawable.widget_pill_yellow)).padding(horizontal = 12.dp, vertical = 4.dp), Color(0xff171412))
                            }
                            Spacer(GlanceModifier.height(8.dp)); Label(project.title(), 18, true, GlanceModifier.height(44.dp).clickable(open(context, "Projects", project.id)))
                        }
                        Label("${project.value("progress").toDoubleOrNull()?.toInt() ?: 0}% · ${project.value("targetDate").ifBlank { "No target date" }}", 11, color = Secondary)
                        Spacer(GlanceModifier.height(if(largeText) 4.dp else if(size.height < 210.dp) 8.dp else 12.dp)); LinearProgressIndicator((project.value("progress").toFloatOrNull() ?: 0f) / 100f, GlanceModifier.fillMaxWidth().height(4.dp), color = ColorProvider(Orange), backgroundColor = ColorProvider(Warm))
                        Spacer(GlanceModifier.height(if(largeText) 4.dp else if(size.height < 210.dp) 8.dp else 12.dp)); if(!largeText) Label("Next action", 11, color = Secondary)
                        val next = tasks.find { it.id == project.value("nextActionId") } ?: tasks.firstOrNull { it.value("projectId") == project.id }
                        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Vertical.CenterVertically) { Label(next?.title() ?: "Choose your next step", 13, true, GlanceModifier.defaultWeight()); WidgetButton("Start", open(context, "Projects", next?.id ?: project.id), GlanceModifier.width(80.dp), color = Blue) }
                    }
                }
            }
        }
    }
}

/** SP conversion also honors Android 14+'s nonlinear accessibility font scaling. */
internal fun widgetTextHeight(context: Context, size: Int): Float {
    val paint = android.text.TextPaint().apply {
        textSize = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, size.toFloat(), context.resources.displayMetrics)
        typeface = context.resources.getFont(R.font.inter)
    }
    val metrics = paint.fontMetricsInt
    return max(size * 1.4f, (metrics.descent - metrics.ascent).toFloat() / context.resources.displayMetrics.density + 1f)
}

@Composable private fun Label(text: String, size: Int = 12, bold: Boolean = false, modifier: GlanceModifier = GlanceModifier, color: Color = Light, description: String? = null) {
    val context = LocalContext.current
    val view = android.widget.RemoteViews(context.packageName, if(bold) R.layout.widget_text_bold else R.layout.widget_text)
    view.setTextViewText(R.id.widget_text, text)
    if(description != null) view.setContentDescription(R.id.widget_text, description)
    view.setTextColor(R.id.widget_text, android.graphics.Color.argb((color.alpha * 255).toInt(), (color.red * 255).toInt(), (color.green * 255).toInt(), (color.blue * 255).toInt()))
    view.setTextViewTextSize(R.id.widget_text, android.util.TypedValue.COMPLEX_UNIT_SP, size.toFloat())
    view.setInt(R.id.widget_text, "setMaxLines", 1)
    AndroidRemoteViews(view, modifier = GlanceModifier.height(widgetTextHeight(context, size).dp).then(modifier))
}
@Composable private fun WidgetButton(text: String, action: Action, modifier: GlanceModifier = GlanceModifier, color: Color = Warm, fontSize: Int = 12, height: Int = 40, description: String? = null) {
    val actualHeight = max(max(44f, height.toFloat()), widgetTextHeight(LocalContext.current, fontSize) + 8)
    Box(modifier.height(actualHeight.dp).background(ImageProvider(if(color == Blue) R.drawable.widget_pill_blue else R.drawable.widget_pill_warm)).clickable(action).padding(horizontal = if(height <= 32) 4.dp else 12.dp, vertical = 4.dp), contentAlignment = Alignment.Center) { Label(text, fontSize, modifier = GlanceModifier, color = if(color == Blue) Color.White else Light, description = description) }
}

class CaptureWidget : NookWidget("capture")
class QuickCaptureWidget : NookWidget("quick")
class InboxWidget : NookWidget("inbox")
class TodayWidget : NookWidget("today")
class ProjectWidget : NookWidget("project")
class CaptureWidgetReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget = CaptureWidget() }
class QuickCaptureWidgetReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget = QuickCaptureWidget() }
class InboxWidgetReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget = InboxWidget() }
class TodayWidgetReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget = TodayWidget() }
class ProjectWidgetReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget = ProjectWidget() }

class CompleteTaskAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val repository = AppGraph.activeRepository(context)
        if(parameters[AccountId] != repository.accountId) return
        val id = parameters[TaskId] ?: return
        val task = repository.get(id) ?: return
        if(task.deleted || task.archived || task.kind != "task" || task.value("completed") == "true") return
        repository.update(id) { it.copy(data = JsonObject(it.data + ("completed" to JsonPrimitive(true)))) }
        scheduleSystemRefresh(context)
    }
}

fun scheduleSystemRefresh(context: Context) {
    WorkManager.getInstance(context).enqueueUniqueWork("nook-system-refresh", ExistingWorkPolicy.APPEND_OR_REPLACE, OneTimeWorkRequestBuilder<SystemRefreshWorker>().build())
}
class SystemRefreshWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = try {
        reconcileReminders(applicationContext)
        listOf(CaptureWidget(), QuickCaptureWidget(), InboxWidget(), TodayWidget(), ProjectWidget()).forEach { it.updateAll(applicationContext) }
        Result.success()
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { Result.retry() }
}
