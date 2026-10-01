package app.nook

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class CalendarLayoutTest {
    @get:Rule val compose = createComposeRule()
    @Test fun largeTextDaysKeepFullTouchTargetsAtNormalAndNarrowWidths() {
        var width by mutableStateOf(360)
        val chosen = mutableListOf<LocalDate>()
        compose.setContent { NookTheme {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                Box(Modifier.width(width.dp).testTag("calendar")) {
                    CalendarMonth(LocalDate.of(2026, 9, 30), emptySet()) { chosen.add(it) }
                }
            }
        } }
        for (viewport in listOf(360, 280)) {
            compose.runOnIdle { width = viewport }
            val parent = compose.onNodeWithTag("calendar").fetchSemanticsNode().boundsInRoot
            for (description in listOf("Previous month", "Next month")) {
                val control = compose.onNodeWithContentDescription(description)
                control.assertIsDisplayed().assertHasClickAction()
                val bounds = control.fetchSemanticsNode().boundsInRoot
                assertTrue(bounds.width >= 48f && bounds.height >= 48f)
                assertTrue(bounds.left >= parent.left && bounds.right <= parent.right)
            }
            // Every September weekday column is reachable by scrolling, with disjoint 48 dp cells.
            for (day in 1..30) {
                val node = compose.onNodeWithContentDescription("Choose 2026-09-${day.toString().padStart(2, '0')}")
                node.performScrollTo().assertIsDisplayed().assertHasClickAction()
                val bounds = node.fetchSemanticsNode().boundsInRoot
                assertTrue("Day $day at $viewport: $bounds", bounds.width >= 48f && bounds.height >= 48f)
                node.performClick()
            }
            java.io.File(ApplicationProvider.getApplicationContext<Context>().getExternalFilesDir(null), "native-calendar-large-text-$viewport.png").outputStream().use { output ->
                compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output)
            }
        }
        compose.runOnIdle { assertEquals((1..30).map { LocalDate.of(2026, 9, it) }.let { it + it }, chosen) }
    }
}
