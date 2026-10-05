package app.nook

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.ParcelFileDescriptor
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.*
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.nook.integration.*
import app.nook.data.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class WidgetRenderTest {
    @Test fun allFiveWidgetsRenderRealLocalRecords() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        AppGraph.initializeFirebase(context)
        val repository = AppGraph.activeRepository(context)
        assertTrue("widget fixtures must use the app's offline namespace", repository.accountId.startsWith("local:"))
        val createdIds = mutableListOf<String>()
        val widgetPreferences = context.getSharedPreferences("nook-widgets", Context.MODE_PRIVATE)
        var widgetKey: String? = null
        var previousWidgetTarget: String? = null
        val host = AppWidgetHost(context, 70132)
        // MediaStore retains screenshots across APK reinstalls. A per-run folder
        // prevents exhausting its numbered duplicate-filename fallback.
        val artifactDirectory = Environment.DIRECTORY_DOWNLOADS + "/NookVisual/" + UUID.randomUUID()
        var projectWidgetId: Int? = null
        try {
            createdIds += repository.capture("Research ESP32 deep sleep").id
            createdIds += repository.capture("Send assessment follow-up").id
            val project = repository.create("project", wireJson.encodeToJsonElement(Project("Classroom Management System", progress = 64.0, targetDate = "2026-10-18")) as JsonObject)
            createdIds += project.id
            val recurrence = repository.create("recurrence", wireJson.encodeToJsonElement(Recurrence("daily", anchorDate = LocalDate.now().toString())) as JsonObject)
            createdIds += recurrence.id
            val task = repository.create("task", wireJson.encodeToJsonElement(Task("Finish ESP32 display prototype", doDate = LocalDate.now().toString(), deadline = LocalDate.now().plusDays(2).toString(), projectId = project.id, recurrenceId = recurrence.id)) as JsonObject)
            createdIds += task.id
            repository.update(project.id) { it.copy(data = wireJson.encodeToJsonElement(Project("Classroom Management System", progress = 64.0, targetDate = "2026-10-18", nextActionId = task.id)) as JsonObject) }
            val undated = repository.create("task", wireJson.encodeToJsonElement(Task("Undated next step")) as JsonObject)
            createdIds += undated.id
            repository.create("project", wireJson.encodeToJsonElement(Project("Undated outcome", nextActionId = undated.id)) as JsonObject)
                .also { createdIds += it.id }
            val hidden = repository.create("task", wireJson.encodeToJsonElement(Task("Archived outcome step")) as JsonObject)
            createdIds += hidden.id
            val archived = repository.create("project", wireJson.encodeToJsonElement(Project("Archived outcome", nextActionId = hidden.id)) as JsonObject)
            createdIds += archived.id
            repository.archive(archived.id, true)
            ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("appwidget grantbind --package app.nook --user 0")).use { it.readBytes() }
            projectWidgetId = host.allocateAppWidgetId()
            assertTrue(AppWidgetManager.getInstance(context).bindAppWidgetIdIfAllowed(projectWidgetId, ComponentName(context, ProjectWidgetReceiver::class.java)))
            widgetKey = "${repository.accountId}:$projectWidgetId"
            previousWidgetTarget = widgetPreferences.getString(widgetKey, null)
            widgetPreferences.edit().putString(widgetKey, project.id).commit()
            val fixtures = listOf(
                Triple("capture", CaptureWidget(), DpSize(128.dp, 128.dp)),
                Triple("quick", QuickCaptureWidget(), DpSize(286.dp, 128.dp)),
                Triple("inbox", InboxWidget(), DpSize(280.dp, 260.dp)),
                Triple("today", TodayWidget(), DpSize(560.dp, 260.dp)),
                Triple("project", ProjectWidget(), DpSize(560.dp, 260.dp)),
                Triple("quick-small", QuickCaptureWidget(), DpSize(200.dp, 88.dp)),
                Triple("project-small", ProjectWidget(), DpSize(360.dp, 180.dp)),
            )
            for((name, widget, size) in fixtures) {
                val views = if(name.startsWith("project")) widget.compose(context, id = GlanceAppWidgetManager(context).getGlanceIdBy(projectWidgetId), size = size) else widget.compose(context, size = size)
                var text = ""
                instrumentation.runOnMainSync {
                    val view = views.apply(context, FrameLayout(context))
                    fun collect(node: View): String = if(node is TextView) node.text.toString() else if(node is ViewGroup) (0 until node.childCount).joinToString(" ") { collect(node.getChildAt(it)) } else ""
                    text = collect(view)
                    val density = context.resources.displayMetrics.density
                    val width = (size.width.value * density).toInt(); val height = (size.height.value * density).toInt()
                    view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)); view.layout(0, 0, width, height)
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888); view.draw(Canvas(bitmap))
                    if(Build.VERSION.SDK_INT >= 29) {
                        val metadata = ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, "widget-$name.png"); put(MediaStore.MediaColumns.MIME_TYPE, "image/png"); put(MediaStore.MediaColumns.RELATIVE_PATH, artifactDirectory) }
                        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, metadata)!!
                        context.contentResolver.openOutputStream(uri)!!.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    }
                }
                when(name) {
                    "capture" -> assertTrue(text.contains("Capture"))
                    "quick" -> assertTrue(text.contains("What’s on your mind?"))
                    "inbox" -> assertTrue("inbox widget text: $text", text.contains("Research ESP32 deep sleep"))
                    "today" -> {
                        assertTrue(text.contains("Finish ESP32 display prototype"))
                        assertTrue(text.contains("2 things worth doing"))
                        assertTrue(text.contains("Do today · Due ${LocalDate.now().plusDays(2)}"))
                        assertTrue(text.contains("Undated next step"))
                        assertTrue(text.contains("Project next action"))
                        assertFalse(text.contains("Archived outcome step"))
                    }
                    "project" -> { assertTrue(text.contains("Classroom Management System")); assertTrue(text.contains("64%")); assertTrue(text.contains("Next action")); assertTrue(text.contains("Start")) }
                }
            }
            val widgetId = GlanceAppWidgetManager(context).getGlanceIdBy(projectWidgetId)
            CompleteTaskAction().onAction(context, widgetId, actionParametersOf(ActionParameters.Key<String>("taskId") to task.id, ActionParameters.Key<String>("accountId") to "local:another-account"))
            assertEquals("false", repository.get(task.id)!!.data["completed"]!!.toString())
            CompleteTaskAction().onAction(context, widgetId, actionParametersOf(ActionParameters.Key<String>("taskId") to task.id, ActionParameters.Key<String>("accountId") to repository.accountId))
            assertEquals("true", repository.get(task.id)!!.data["completed"]!!.toString())
            val completed = repository.get(task.id)!!
            val nextId = repository.get(project.id)!!.data["nextActionId"]!!.jsonPrimitive.content
            assertNotEquals(task.id, nextId)
            val next = wireJson.decodeFromJsonElement(Task.serializer(), repository.get(nextId)!!.data)
            assertFalse(next.completed)
            assertEquals(LocalDate.now().plusDays(1).toString(), next.doDate)
            assertEquals(LocalDate.now().plusDays(3).toString(), next.deadline)
            // A repeated/stale checkbox callback must not produce another completion or occurrence.
            CompleteTaskAction().onAction(context, widgetId, actionParametersOf(ActionParameters.Key<String>("taskId") to task.id, ActionParameters.Key<String>("accountId") to repository.accountId))
            assertEquals(completed, repository.get(task.id))
            assertEquals(2, repository.db.records().byKinds(repository.accountId, listOf("task")).map { it.decode() }
                .count { it.data["recurrenceId"] == JsonPrimitive(recurrence.id) })
            assertEquals(nextId, repository.get(project.id)!!.data["nextActionId"]!!.jsonPrimitive.content)
            CompleteTaskAction().onAction(context, widgetId, actionParametersOf(ActionParameters.Key<String>("taskId") to undated.id, ActionParameters.Key<String>("accountId") to repository.accountId))
            val refreshed = TodayWidget().compose(context, size = DpSize(560.dp, 260.dp))
            var refreshedText = ""
            instrumentation.runOnMainSync {
                val view = refreshed.apply(context, FrameLayout(context))
                fun collect(node: View): String = if(node is TextView) node.text.toString() else if(node is ViewGroup) (0 until node.childCount).joinToString(" ") { collect(node.getChildAt(it)) } else ""
                refreshedText = collect(view)
            }
            assertTrue(refreshedText.contains("1 thing worth doing"))
            assertFalse(refreshedText.contains("Undated next step"))
            // Tomorrow's recurring occurrence is a Project next action and remains useful today.
            assertTrue(refreshedText.contains("Finish ESP32 display prototype"))
            assertTrue(refreshedText.contains("Do ${LocalDate.now().plusDays(1)} · Due ${LocalDate.now().plusDays(3)}"))

        } finally {
            projectWidgetId?.let { host.deleteAppWidgetId(it) }
            widgetKey?.let { key ->
                val editor = widgetPreferences.edit()
                if(previousWidgetTarget == null) editor.remove(key) else editor.putString(key, previousWidgetTarget)
                editor.commit()
            }
            createdIds.asReversed().forEach { id -> repository.get(id)?.takeIf { !it.deleted }?.let { repository.delete(id) } }
        }
    }
}
