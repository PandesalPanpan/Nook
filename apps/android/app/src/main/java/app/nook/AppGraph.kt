package app.nook

import android.content.Context
import androidx.room.Room
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.Lifecycle
import app.nook.data.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class FirebaseRuntime(val ready: Boolean = false, val auth: AuthSession? = null, val error: String? = null, val actions: AccountActions? = null, val googleWebClientId: String? = null)

/** One database per process; every entry surface uses the active account namespace. */
object AppGraph {
    private val processScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val initialization = Mutex()
    private val mutableFirebase = MutableStateFlow(FirebaseRuntime())
    val firebase = mutableFirebase.asStateFlow()
    /** Restore cached Auth before a cold-start system surface selects its local namespace. No network wait. */
    suspend fun activeRepository(context: Context): NookRepository = withContext(Dispatchers.IO) {
        initializeFirebase(context.applicationContext)
        val auth = mutableFirebase.value.auth
        if (auth == null) repository(context)
        else auth.accounts.state.first { state ->
            val uid = auth.identity()?.uid
            !state.changing && (uid == state.repository.accountId || uid == null && state.repository.accountId.startsWith("local:"))
        }.repository
    }
    suspend fun initializeFirebase(context: Context) = initialization.withLock {
        if (mutableFirebase.value.ready) return@withLock
        try {
            val configuration = loadFirebaseConfiguration(context)
            if (configuration == null) {
                mutableFirebase.value = FirebaseRuntime(ready = true)
                return@withLock
            }
            val services = initializeNookFirebase(context.applicationContext, configuration.options(), configuration.emulatorHost)
            val preferences = accounts(context)
            val guest = NookRepository(database(context), preferences.guestAccountId, preferences.clientId)
            suspend fun publishNamespace(next: NookRepository) {
                val previous = preferences.activeAccountId()
                if (previous != next.accountId) app.nook.integration.cancelAccountReminders(context.applicationContext, previous)
                preferences.activate(next.accountId)
            }
            val scheduler = NativeSyncScheduler(processScope, enqueue = { uid -> scheduleSync(context.applicationContext, uid) },
                periodic = { uid -> schedulePeriodicSync(context.applicationContext, uid) },
                foreground = ProcessLifecycleOwner.get().lifecycle.currentStateFlow.map { it.isAtLeast(Lifecycle.State.STARTED) })
            val session = AccountSession(guest, activated = { next -> publishNamespace(next) }, paused = { scheduler.stop() },
                resumed = { next -> scheduler.start(next); if (mutableFirebase.value.ready) app.nook.integration.scheduleSystemRefresh(context.applicationContext) }) { uid -> FirebaseTransport(services.firestore, uid) }
            val signedOut=SignedOutFence(context.applicationContext.getSharedPreferences("nook-auth-local",Context.MODE_PRIVATE),"${configuration.projectId}:${configuration.applicationId}")
            val auth = AuthSession(session, FirebaseAuthPort(services.auth, signedOut, clearCredentials = { app.nook.integration.GoogleSignIn.clear(context.applicationContext) }), processScope, beforeCredentialChange = { cancelSyncAndWait(context) })
            auth.initialize()
            publishNamespace(session.state.value.repository)
            mutableFirebase.value = FirebaseRuntime(ready = true, auth = auth, actions = AccountActions(auth, processScope), googleWebClientId = configuration.googleWebClientId)
            app.nook.integration.scheduleSystemRefresh(context.applicationContext)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            mutableFirebase.value = FirebaseRuntime(ready = true, error = error.message ?: "Cloud setup could not start")
        }
    }
    @Volatile private var database: NookDatabase? = null
    @Volatile private var accountPreferences: AccountPreferences? = null
    fun accounts(context: Context): AccountPreferences = accountPreferences ?: synchronized(this) {
        accountPreferences ?: AccountPreferences(
            context.applicationContext.getSharedPreferences("nook-local", Context.MODE_PRIVATE),
            context.applicationContext.getSharedPreferences("nook-session", Context.MODE_PRIVATE)
        ).also { accountPreferences = it }
    }
    fun database(context: Context): NookDatabase = database ?: synchronized(this) {
        database ?: Room.databaseBuilder(context.applicationContext, NookDatabase::class.java, "nook.db")
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).build().also { database = it }
    }
    fun repository(context: Context): NookRepository {
        val accounts = accounts(context)
        return NookRepository(database(context), accounts.activeAccountId(), accounts.clientId)
    }
}
