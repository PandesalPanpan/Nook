package app.nook.updates

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdatePolicyTest {
    @Test fun versionCodeComparisonIsNumericAndNeverDowngrades() {
        assertTrue(UpdatePolicy.isUpdateAvailable(1, 2))
        assertFalse(UpdatePolicy.isUpdateAvailable(2, 2))
        assertFalse(UpdatePolicy.isUpdateAvailable(3, 2))
    }

    @Test fun automaticChecksWaitSixHoursAfterSuccess() {
        val now = 1_000_000_000L
        assertTrue(UpdatePolicy.shouldCheckAutomatically(now, 0, 0))
        assertFalse(UpdatePolicy.shouldCheckAutomatically(now, now - 60 * 60 * 1000L, now - 60 * 60 * 1000L))
        assertTrue(UpdatePolicy.shouldCheckAutomatically(now, now - 6 * 60 * 60 * 1000L, now - 6 * 60 * 60 * 1000L))
    }

    @Test fun failedAutomaticCheckGetsAQuietRetryWindow() {
        val now = 1_000_000_000L
        assertFalse(UpdatePolicy.shouldCheckAutomatically(now, now - 7 * 60 * 60 * 1000L, now - 5 * 60 * 1000L))
        assertTrue(UpdatePolicy.shouldCheckAutomatically(now, now - 7 * 60 * 60 * 1000L, now - 15 * 60 * 1000L))
    }

    @Test fun manualCheckAlwaysBypassesAutomaticThrottle() {
        val now = 1_000_000_000L
        val recentlyChecked = now - 60_000L
        assertFalse(UpdatePolicy.shouldCheck(now, recentlyChecked, recentlyChecked, manual = false))
        assertTrue(UpdatePolicy.shouldCheck(now, recentlyChecked, recentlyChecked, manual = true))
    }

    @Test fun laterDismissesOnlyThatVersionAndManualCheckCanShowItAgain() {
        assertFalse(UpdatePolicy.shouldShowUpdate(2, 1, dismissedVersionCode = 2, manual = false))
        assertTrue(UpdatePolicy.shouldShowUpdate(2, 1, dismissedVersionCode = 2, manual = true))
        assertTrue(UpdatePolicy.shouldShowUpdate(3, 1, dismissedVersionCode = 2, manual = false))
    }
}
