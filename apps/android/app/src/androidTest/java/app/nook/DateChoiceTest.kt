package app.nook

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import android.view.accessibility.AccessibilityNodeInfo
import java.time.LocalDate
import java.time.LocalTime
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class DateChoiceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun inAppCalendarShortcutsClearAndClockKeepTheirLocalValues() {
        var date by mutableStateOf("")
        var time by mutableStateOf(LocalTime.of(21, 30))
        compose.setContent { NookTheme { Column {
            DateChoice("Schedule", date) { date = it }
            TimeChoice("Reminder time", time) { time = it }
        } } }
        compose.onNodeWithText("Schedule · Choose date").performClick()
        compose.onAllNodesWithText("Tomorrow").onLast().performClick()
        compose.onNodeWithText("Set date").performClick()
        compose.runOnIdle { assertEquals(LocalDate.now().plusDays(1).toString(), date) }
        compose.onNodeWithText("Tomorrow").performClick()
        compose.onNode(hasText("Schedule ·", substring = true) and hasClickAction()).performClick()
        compose.onAllNodesWithText("Today").onLast().performClick()
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertEquals(LocalDate.now().plusDays(1).toString(), date) }
        compose.onNode(hasText("Reminder time ·", substring = true) and hasClickAction()).performClick()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        compose.waitUntil(5000) { automation.rootInActiveWindow?.findAccessibilityNodeInfosByViewId("android:id/button1")?.isNotEmpty() == true }
        assertTrue(automation.rootInActiveWindow.findAccessibilityNodeInfosByViewId("android:id/button1").first().performAction(AccessibilityNodeInfo.ACTION_CLICK))
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(LocalTime.of(21, 30), time) }
        compose.onNodeWithText("Clear Schedule").performClick()
        compose.runOnIdle { assertEquals("", date) }
    }
}
