package app.nook

import java.time.Instant
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Test

class CaptureTimeTest {
    @Test fun recentCapturesUseRelativeTimeAndOlderCapturesUseDateAndTime() {
        val previousLocale = Locale.getDefault()
        val previousTimeZone = TimeZone.getDefault()
        try {
            Locale.setDefault(Locale.US)
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            val now = Instant.parse("2026-10-05T12:00:00Z").toEpochMilli()

            assertEquals("just now", formatCaptureTime(now - 30_000, now))
            assertEquals("45 minutes ago", formatCaptureTime(now - 45 * 60_000, now))
            assertEquals("Oct 5, 2026 · 10:00\u202fAM", formatCaptureTime(now - 2 * 60 * 60_000, now))
        } finally {
            Locale.setDefault(previousLocale)
            TimeZone.setDefault(previousTimeZone)
        }
    }
}
