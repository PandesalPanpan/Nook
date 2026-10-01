package app.nook

import android.app.NotificationManager
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.os.Process
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.compose
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import app.nook.data.*
import app.nook.integration.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.util.UUID

/** Invoked in separate OS processes by test-native-cold-surfaces.ps1, never a simulated singleton reset. */
class ColdAccountSurfaceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val fixture get() = context.getSharedPreferences("nook-cold-fixture",Context.MODE_PRIVATE)
    private fun enabled() = assumeTrue(InstrumentationRegistry.getArguments().getString("configuredCold") == "true")
    private suspend fun restored(): NookRepository {
        val network=context.getSystemService(android.net.ConnectivityManager::class.java)
        assertTrue("Cold system surfaces must work offline",network.getNetworkCapabilities(network.activeNetwork)?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)!=true)
        val priorPid=fixture.getInt("pid",-1)
        assertTrue("Gate must restart the Android application process",priorPid>0 && priorPid!=Process.myPid())
        val repository=AppGraph.activeRepository(context)
        assertEquals(fixture.getString("uid",null),repository.accountId)
        assertEquals(repository.accountId,AppGraph.firebase.value.auth?.identity()?.uid)
        assertNull(repository.get(requireNotNull(fixture.getString("guestCapture",null))))
        assertNotNull(repository.get(requireNotNull(fixture.getString("capture",null))))
        fixture.edit().putInt("pid",Process.myPid()).commit()
        android.util.Log.i("NookColdSurface","cached-auth-restored pid=${Process.myPid()} previous=$priorPid")
        return repository
    }
    @Test fun prepare(): Unit = runBlocking {
        enabled()
        AppGraph.initializeFirebase(context)
        val auth=requireNotNull(AppGraph.firebase.value.auth) { AppGraph.firebase.value.error.orEmpty() }
        assertNull("Use a clean emulator test account session",auth.identity())
        auth.signIn("cold-${UUID.randomUUID()}@example.com","test-only-cold-password",createAccount=true)
        val repository=AppGraph.activeRepository(context)
        val capture=repository.capture("Cold authenticated Inbox")
        val project=repository.create("project",wireJson.encodeToJsonElement(Project("Cold authenticated Project",progress=64.0)) as JsonObject)
        val task=repository.create("task",wireJson.encodeToJsonElement(Task("Cold authenticated next action",doDate=java.time.LocalDate.now().toString(),projectId=project.id)) as JsonObject)
        val completion=repository.create("task",wireJson.encodeToJsonElement(Task("Cold widget completion",doDate=java.time.LocalDate.now().toString())) as JsonObject)
        repository.update(project.id) { it.copy(data=JsonObject(it.data+("nextActionId" to JsonPrimitive(task.id)))) }
        val reminder=repository.create("reminder",wireJson.encodeToJsonElement(Reminder(task.id,System.currentTimeMillis()-1000,"deadline")) as JsonObject)
        val guest=NookRepository(repository.db,AppGraph.accounts(context).guestAccountId,repository.clientId)
        val guestBody="Private guest namespace ${UUID.randomUUID()}"
        val guestCapture=guest.capture(guestBody)
        fixture.edit().putString("uid",repository.accountId).putString("capture",capture.id).putString("project",project.id).putString("task",task.id)
            .putString("reminder",reminder.id).putString("completionTask",completion.id).putString("guestCapture",guestCapture.id).putString("guestBody",guestBody).putInt("pid",Process.myPid()).commit()
        context.getSharedPreferences("nook-local",Context.MODE_PRIVATE).edit().putBoolean("onboarded",true).commit()
    }
    @Test fun widget() = runBlocking {
        enabled()
        val uid=requireNotNull(fixture.getString("uid",null))
        val host=AppWidgetHost(context,70133)
        val id=host.allocateAppWidgetId()
        try {
            assertTrue(AppWidgetManager.getInstance(context).bindAppWidgetIdIfAllowed(id,ComponentName(context,ProjectWidgetReceiver::class.java)))
            context.getSharedPreferences("nook-widgets",Context.MODE_PRIVATE).edit().putString("$uid:$id",fixture.getString("project",null)).commit()
            for((name,widget) in listOf("Inbox" to InboxWidget(),"Today" to TodayWidget(),"Project" to ProjectWidget())) {
                val remote=widget.compose(context,size=DpSize(560.dp,260.dp),id=GlanceAppWidgetManager(context).getGlanceIdBy(id))
                var text=""
                InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    fun collect(view:View):String=when(view){is TextView->view.text.toString();is ViewGroup->(0 until view.childCount).joinToString(" "){collect(view.getChildAt(it))};else->""}
                    text=collect(remote.apply(context,FrameLayout(context)))
                }
                assertFalse(text.contains(requireNotNull(fixture.getString("guestBody",null))))
                assertTrue("$name must render the cached authenticated namespace: $text",text.contains(if(name=="Inbox")"Cold authenticated Inbox" else if(name=="Today")"Cold authenticated next action" else "Cold authenticated Project"))
            }
            // The production widget, rather than a test setup call, performs first Auth restoration.
            val repository=restored()
            val glance=GlanceAppWidgetManager(context).getGlanceIdBy(id)
            val taskId=requireNotNull(fixture.getString("task",null))
            CompleteTaskAction().onAction(context,glance,actionParametersOf(ActionParameters.Key<String>("taskId") to taskId,ActionParameters.Key<String>("accountId") to AppGraph.accounts(context).guestAccountId))
            assertEquals("false",repository.get(taskId)!!.data["completed"]!!.jsonPrimitive.content)
            val completionId=requireNotNull(fixture.getString("completionTask",null))
            CompleteTaskAction().onAction(context,glance,actionParametersOf(ActionParameters.Key<String>("taskId") to completionId,ActionParameters.Key<String>("accountId") to repository.accountId))
            assertEquals("true",repository.get(completionId)!!.data["completed"]!!.jsonPrimitive.content)
            assertNotNull(repository.db.records().operation(repository.accountId,completionId))
        } finally {
            context.getSharedPreferences("nook-widgets",Context.MODE_PRIVATE).edit().remove("$uid:$id").commit()
            host.deleteAppWidgetId(id)
        }
    }
    @Test fun reminder() = runBlocking {
        enabled()
        // Read only the durable fixture to prepare WorkData; no Auth/runtime initialization here.
        val repository=NookRepository(AppGraph.database(context),requireNotNull(fixture.getString("uid",null)),AppGraph.accounts(context).clientId)
        val manager=context.getSystemService(NotificationManager::class.java)
        manager.cancelAll()
        val record=repository.get(requireNotNull(fixture.getString("reminder",null)))!!
        // The fixture reminder may already have been delivered by normal reconciliation before the stop.
        val due=repository.update(record.id){it.copy(archived=false)}
        val worker=TestListenableWorkerBuilder<ReminderWorker>(context).setInputData(workDataOf("accountId" to repository.accountId,"reminderId" to due.id,"version" to due.updatedAt)).build()
        worker.doWork()
        assertEquals(repository.accountId,restored().accountId)
        assertTrue(manager.activeNotifications.any{it.notification.extras.getString("android.title")=="Cold authenticated next action" && it.notification.extras.getString("android.text")=="Deadline reminder"})
        assertTrue(repository.get(due.id)!!.archived)
        val posted=manager.activeNotifications.single{it.notification.extras.getString("android.title")=="Cold authenticated next action"}.notification
        assertEquals(listOf("Capture","Open","Later"),posted.actions.map{it.title.toString()})
        manager.cancelAll()
    }
    @Test fun shortcut() {
        enabled()
        val shortcut=context.getSystemService(android.content.pm.ShortcutManager::class.java).manifestShortcuts.single{it.id=="task"}
        ActivityScenario.launch<CaptureActivity>(requireNotNull(shortcut.intent)).use {
            compose.waitUntil(10000){compose.onAllNodesWithText("Thought",substring=false).fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithText("Thought").performTextInput("Cold shortcut task capture")
            compose.waitUntil(10000){compose.onAllNodesWithText("Save",substring=false).filter(isEnabled()).fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithText("Save",substring=false).performClick()
            val repository=runBlocking{restored()}
            compose.waitUntil(5000){runBlocking{repository.db.records().byKinds(repository.accountId,listOf("capture")).any{it.decode().data["body"]?.jsonPrimitive?.content=="Cold shortcut task capture"}}}
            val capture=runBlocking{repository.db.records().byKinds(repository.accountId,listOf("capture")).map{it.decode()}.single{it.data["body"]?.jsonPrimitive?.content=="Cold shortcut task capture"}}
            assertEquals("task",capture.data["captureType"]!!.jsonPrimitive.content)
            assertNotNull(runBlocking{repository.db.records().operation(repository.accountId,capture.id)})
            fixture.edit().putString("shortcutCapture",capture.id).commit()
        }
    }
    @Test fun cleanup(): Unit = runBlocking {
        enabled()
        AppGraph.initializeFirebase(context)
        val repository=AppGraph.activeRepository(context)
        if(repository.accountId==fixture.getString("uid",null)) {
            for(key in listOf("capture","project","task","completionTask","reminder","shortcutCapture")) fixture.getString(key,null)?.let { id -> repository.get(id)?.takeUnless{it.deleted}?.let{repository.delete(id)} }
        }
        val guest=NookRepository(repository.db,AppGraph.accounts(context).guestAccountId,repository.clientId)
        fixture.getString("guestCapture",null)?.let { id -> guest.get(id)?.takeUnless{it.deleted}?.let{guest.delete(id)} }
        val auth=requireNotNull(AppGraph.firebase.value.auth) { AppGraph.firebase.value.error.orEmpty() }
        auth.signOut()
        assertNull(auth.identity())
        context.getSystemService(NotificationManager::class.java).cancelAll()
        fixture.edit().clear().putInt("pid",Process.myPid()).commit()
    }
    @Test fun disconnected() = runBlocking {
        enabled()
        assertNotEquals(fixture.getInt("pid",-1),Process.myPid())
        val repository=AppGraph.activeRepository(context)
        val auth=requireNotNull(AppGraph.firebase.value.auth) { AppGraph.firebase.value.error.orEmpty() }
        assertNull("Sign-out must survive an immediate process restart",auth.identity())
        assertEquals(AppGraph.accounts(context).guestAccountId,repository.accountId)
        fixture.edit().clear().commit()
        Unit
    }
}
