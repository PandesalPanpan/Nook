package app.nook

import android.content.Context
import android.app.NotificationManager
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import app.nook.integration.ReminderWorker
import app.nook.data.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ReminderDeliveryTest {
    @Test fun actualWorkerDeliversTaskAndSkipsEmptyInboxOffline() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val session = context.getSharedPreferences("nook-session", Context.MODE_PRIVATE)
        val previous = session.getString("accountId", null)
        session.edit().putString("accountId", "local:notification-${UUID.randomUUID()}").commit()
        val manager = context.getSystemService(NotificationManager::class.java)
        ParcelFileDescriptor.AutoCloseInputStream(InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("pm grant app.nook android.permission.POST_NOTIFICATIONS")).use { it.readBytes() }
        try {
            val repository = AppGraph.repository(context)
            val task = repository.create("task", wireJson.encodeToJsonElement(Task("Verified native reminder")) as JsonObject)
            val reminder = repository.create("reminder", wireJson.encodeToJsonElement(Reminder(task.id, System.currentTimeMillis() - 1000, "task")) as JsonObject)
            suspend fun deliver(record: app.nook.data.Record) = TestListenableWorkerBuilder<ReminderWorker>(context)
                .setInputData(workDataOf("accountId" to repository.accountId, "reminderId" to record.id, "version" to record.updatedAt)).build().doWork()
            deliver(reminder)
            assertTrue(manager.activeNotifications.any { it.notification.extras.getString("android.title") == "Verified native reminder" })
            assertTrue(repository.get(reminder.id)!!.archived)
            val before = manager.activeNotifications.size
            val inbox = repository.create("reminder", wireJson.encodeToJsonElement(Reminder(scheduledAt = System.currentTimeMillis() - 1000, type = "inbox")) as JsonObject)
            deliver(inbox)
            assertEquals(before, manager.activeNotifications.size)
            assertTrue(repository.get(inbox.id)!!.updatedAt > inbox.updatedAt)
            manager.cancelAll()
        } finally {
            if(previous == null) session.edit().remove("accountId").commit() else session.edit().putString("accountId", previous).commit()
        }
    }
}
