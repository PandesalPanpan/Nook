package app.nook.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AccountPreferencesTest {
    @Test fun identitiesAndActiveAccountSurviveRecreationAndSignOut() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val local = context.getSharedPreferences("local-${UUID.randomUUID()}", Context.MODE_PRIVATE)
        val session = context.getSharedPreferences("session-${UUID.randomUUID()}", Context.MODE_PRIVATE)
        val first = AccountPreferences(local, session)
        first.activate("alice")
        val second = AccountPreferences(local, session)
        assertEquals(first.clientId, second.clientId)
        assertEquals(first.guestAccountId, second.guestAccountId)
        assertEquals("alice", second.accountId.value)
        second.activate(null)
        assertEquals(first.guestAccountId, second.accountId.value)
        assertEquals(first.clientId, second.clientId)
        assertNull(session.getString("accountId", null))
        first.close(); second.close()
    }
}
