package app.nook

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.ParcelFileDescriptor
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.*
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import app.nook.data.*
import app.nook.integration.*
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class WidgetFontScaleTest {
    @Test fun realRemoteViewsKeepScaledTextAndActionsInsideSmallAndNormalWidgets() = runBlocking {
        val original = ApplicationProvider.getApplicationContext<Context>()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        AppGraph.initializeFirebase(original)
        val repo = AppGraph.activeRepository(original)
        assertTrue("widget fixtures must use the app's offline namespace", repo.accountId.startsWith("local:"))
        val createdIds = mutableListOf<String>()
        val widgetPreferences = original.getSharedPreferences("nook-widgets", Context.MODE_PRIVATE)
        var widgetKey: String? = null
        var previousWidgetTarget: String? = null
        val host = AppWidgetHost(original, 70133)
        var widgetId: Int? = null
        try {
            repeat(3) { createdIds += repo.capture("A long thought to clarify later $it").id }
            val project = repo.create("project", wireJson.encodeToJsonElement(Project("Classroom Management System", progress = 64.0, targetDate = "2026-10-18")) as JsonObject)
            createdIds += project.id
            val task = repo.create("task", wireJson.encodeToJsonElement(Task("Finish the display prototype", doDate = LocalDate.now().toString(), projectId = project.id)) as JsonObject)
            createdIds += task.id
            repo.update(project.id) { it.copy(data = JsonObject(it.data + ("nextActionId" to JsonPrimitive(task.id)))) }
            ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("appwidget grantbind --package app.nook --user 0")).use { it.readBytes() }
            widgetId = host.allocateAppWidgetId()
            assertTrue(AppWidgetManager.getInstance(original).bindAppWidgetIdIfAllowed(widgetId, ComponentName(original, ProjectWidgetReceiver::class.java)))
            widgetKey = "${repo.accountId}:$widgetId"
            previousWidgetTarget = widgetPreferences.getString(widgetKey, null)
            widgetPreferences.edit().putString(widgetKey, project.id).commit()
            val fixtures = listOf(
                Triple("capture-small", CaptureWidget(), DpSize(56.dp, 56.dp)),
                Triple("capture", CaptureWidget(), DpSize(128.dp, 128.dp)),
                Triple("quick-small", QuickCaptureWidget(), DpSize(200.dp, 88.dp)),
                Triple("quick", QuickCaptureWidget(), DpSize(286.dp, 128.dp)),
                Triple("inbox-small", InboxWidget(), DpSize(180.dp, 180.dp)),
                Triple("inbox", InboxWidget(), DpSize(280.dp, 260.dp)),
                Triple("today-small", TodayWidget(), DpSize(320.dp, 180.dp)),
                Triple("today", TodayWidget(), DpSize(560.dp, 260.dp)),
                Triple("project-small", ProjectWidget(), DpSize(320.dp, 180.dp)),
                Triple("project", ProjectWidget(), DpSize(560.dp, 260.dp)),
            )
            for(scale in listOf(1f, 2f)) {
                val context = original.createConfigurationContext(Configuration(original.resources.configuration).apply { fontScale = scale })
                for((name, widget, size) in fixtures) {
                    val remote = if(name.startsWith("project")) widget.compose(context, id = GlanceAppWidgetManager(context).getGlanceIdBy(widgetId), size = size) else widget.compose(context, size = size)
                    instrumentation.runOnMainSync {
                        val container = FrameLayout(context)
                        val view = remote.apply(context, container)
                        val density = context.resources.displayMetrics.density
                        val width = (size.width.value * density).toInt()
                        val height = (size.height.value * density).toInt()
                        container.addView(view)
                        container.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                        container.layout(0, 0, width, height)
                        val texts = mutableListOf<TextView>()
                        fun visit(node: View) {
                            if(node is TextView && node.text.isNotEmpty()) texts.add(node)
                            if(node is ViewGroup) repeat(node.childCount) { visit(node.getChildAt(it)) }
                        }
                        visit(view)
                        assertTrue("$name at $scale must render text", texts.isNotEmpty())
                        if(name == "capture") {
                            val plus = texts.single { it.text.toString() == "+" }
                            val caption = texts.single { it.text.toString() == "Capture" }
                            val plusBounds = Rect(0, 0, plus.width, plus.height).also { container.offsetDescendantRectToMyCoords(plus, it) }
                            val captionBounds = Rect(0, 0, caption.width, caption.height).also { container.offsetDescendantRectToMyCoords(caption, it) }
                            val groupCenter = (plusBounds.top + captionBounds.bottom) / 2f
                            assertTrue("Capture glyph and label must be centered together at $scale: $plusBounds / $captionBounds / $height", kotlin.math.abs(groupCenter - height / 2f) <= 2 * density)
                        }
                        for(text in texts) {
                            val bounds = Rect(0, 0, text.width, text.height)
                            container.offsetDescendantRectToMyCoords(text, bounds)
                            assertTrue("$name at $scale: ${text.text} outside $bounds / $width × $height", bounds.left >= 0 && bounds.top >= 0 && bounds.right <= width && bounds.bottom <= height)
                            assertTrue("$name at $scale: ${text.text} clipped vertically: ${text.layout?.height} / ${text.height}", text.layout != null && text.layout.height <= text.height - text.compoundPaddingTop - text.compoundPaddingBottom)
                            if(text.text.toString() in setOf("Text", "Task", "Photo", "Link", "Process", "Process inbox", "Capture", "+ Capture", "Start")) assertEquals("$name at $scale must show the full action ${text.text}", 0, text.layout.getEllipsisCount(0))
                            if(text.text.toString() in setOf("Inbox", "Today", "Classroom Management System")) assertEquals("$name must honor the actual SP scale", android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, 18f, context.resources.displayMetrics), text.textSize, 0.1f)
                            var action: View? = text
                            while(action != null && !action.isClickable) action = action.parent as? View
                            action?.let { assertTrue("$name: ${text.text} small action ${it.width / density} × ${it.height / density}", it.width / density >= 43.5f && it.height / density >= 43.5f) }
                        }
                        if(name.startsWith("quick")) assertEquals(setOf("Text", "Task", "Photo", "Link"), texts.map { it.text.toString() }.filter { it in setOf("Text", "Task", "Photo", "Link") }.toSet())
                        if(name.startsWith("inbox")) { assertTrue(texts.any { it.text.toString().startsWith("Process") }); assertTrue(texts.any { it.text.toString() in setOf("+ Capture", "Capture") || it.contentDescription?.toString() == "Quick capture" }) }
                        if(name.startsWith("today")) assertTrue("$name at $scale: ${texts.map { it.text }}", texts.any { it.text.toString() == "Finish the display prototype" })
                        if(name.startsWith("project")) { assertTrue(texts.any { it.text.toString() == "Classroom Management System" }); assertTrue(texts.any { it.text.toString().contains("64%") }); assertTrue(texts.any { it.text.toString() == "Start" }) }
                        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        container.draw(Canvas(bitmap))
                        File(original.getExternalFilesDir(null), "widget-$name-font-${scale.toInt()}.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    }
                }
            }
        } finally {
            widgetId?.let { host.deleteAppWidgetId(it) }
            widgetKey?.let { key ->
                val editor = widgetPreferences.edit()
                if(previousWidgetTarget == null) editor.remove(key) else editor.putString(key, previousWidgetTarget)
                editor.commit()
            }
            createdIds.asReversed().forEach { id -> repo.get(id)?.takeIf { !it.deleted }?.let { repo.delete(id) } }
        }
    }
}
