package app.nook.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class NativeSyncSchedulerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, NookDatabase::class.java).build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val guest = NookRepository(db, "local:scheduler", "device")
    private val alice = NookRepository(db, "alice", "device")
    @After fun close() { scope.cancel(); db.close() }

    @Test fun remotePullRequestsFollowForegroundVisibilityAndStopWithTheAccount() = runBlocking {
        val foreground = MutableStateFlow(false)
        val queued = Channel<String>(Channel.UNLIMITED)
        val scheduler = NativeSyncScheduler(scope, { queued.send(it) }, {}, debounceMillis = 0,
            foreground = foreground, foregroundIntervalMillis = 40)
        try {
            scheduler.start(alice)
            assertEquals("alice", withTimeout(5000) { queued.receive() })
            foreground.value = true
            assertEquals("alice", withTimeout(5000) { queued.receive() })
            assertEquals("alice", withTimeout(5000) { queued.receive() })
            foreground.value = false
            delay(100)
            while (queued.tryReceive().isSuccess) { }
            delay(100); assertTrue(queued.tryReceive().isFailure)
            foreground.value = true
            assertEquals("alice", withTimeout(5000) { queued.receive() })
            scheduler.stop()
            while (queued.tryReceive().isSuccess) { }
            delay(100); assertTrue(queued.tryReceive().isFailure)
        } finally { scheduler.stop() }
    }

    @Test fun committedChangesScheduleButLocalDataRetriesAndAcknowledgementsDoNotLoop() = runBlocking {
        val queued = Channel<String>(Channel.UNLIMITED)
        val periodic = Channel<String>(Channel.UNLIMITED)
        val scheduler = NativeSyncScheduler(scope, { queued.send(it) }, { periodic.send(it) }, debounceMillis = 0)
        try {
            scheduler.start(guest); guest.capture("Local-only thought")
            delay(100)
            assertTrue(queued.tryReceive().isFailure); assertTrue(periodic.tryReceive().isFailure)
            scheduler.start(alice)
            assertEquals("alice", withTimeout(5000) { periodic.receive() })
            assertEquals("alice", withTimeout(5000) { queued.receive() })
            val record = alice.capture("Committed cloud thought")
            assertEquals("alice", withTimeout(5000) { queued.receive() })
            val operation = requireNotNull(db.records().operation("alice", record.id))
            db.records().enqueue(operation.copy(attempts = 7, nextAttemptAt = 50_000))
            delay(100); assertTrue(queued.tryReceive().isFailure)
            db.records().acknowledge("alice", record.id)
            delay(100); assertTrue(queued.tryReceive().isFailure)
            alice.capture("Observer still works")
            assertEquals("alice", withTimeout(5000) { queued.receive() })
            scheduler.stop(); alice.capture("After sign-out barrier")
            delay(100); assertTrue(queued.tryReceive().isFailure)
        } finally { scheduler.stop() }
    }
    @Test fun stoppingJoinsAnUnresolvedEnqueueBeforeTheCredentialBarrier() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val scheduler = NativeSyncScheduler(scope, {
            started.complete(Unit)
            try { awaitCancellation() } finally { cancelled.complete(Unit) }
        }, {}, debounceMillis = 0)
        scheduler.start(alice)
        withTimeout(5000) { started.await() }
        withTimeout(1000) { scheduler.stop() }
        assertTrue(cancelled.isCompleted)
        assertEquals("alice", alice.accountId)
    }
    @Test fun schedulingFailureRetriesWithoutRequiringAnotherLocalMutation() = runBlocking {
        val attempts = AtomicInteger()
        val queued = CompletableDeferred<String>()
        val record = alice.capture("Durable before scheduler starts")
        val scheduler = NativeSyncScheduler(scope, {
            if (attempts.incrementAndGet() == 1) error("Temporary WorkManager failure")
            queued.complete(it)
        }, {}, debounceMillis = 0, retryMillis = 20)
        try {
            scheduler.start(alice)
            assertEquals("alice", withTimeout(5000) { queued.await() })
            assertEquals(2, attempts.get())
            assertNotNull(db.records().operation("alice", record.id))
        } finally { scheduler.stop() }
    }
}
