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

    @Test fun calendarAndClockDialogsSelectValuesWithoutTypingAndCancelPreservesDate() {
        var date by mutableStateOf("")
        var time by mutableStateOf(LocalTime.of(21, 30))
        compose.setContent { NookTheme { Column {
            DateChoice("Do date", date) { date = it }
            TimeChoice("Reminder time", time) { time = it }
        } } }
        fun pressDialogButton(id: String) {
            val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
            compose.waitUntil(5000) { automation.rootInActiveWindow?.findAccessibilityNodeInfosByViewId(id)?.isNotEmpty() == true }
            assertTrue(automation.rootInActiveWindow.findAccessibilityNodeInfosByViewId(id).first().performAction(AccessibilityNodeInfo.ACTION_CLICK))
            compose.waitForIdle()
        }
        compose.onNodeWithText("Do date · Choose date").performClick()
        pressDialogButton("android:id/button1")
        compose.runOnIdle { assertEquals(LocalDate.now().toString(), date) }
        compose.onNodeWithText("Tomorrow").performClick()
        compose.onNode(hasText("Do date ·", substring = true) and hasClickAction()).performClick()
        pressDialogButton("android:id/button2")
        compose.runOnIdle { assertEquals(LocalDate.now().plusDays(1).toString(), date) }
        compose.onNode(hasText("Reminder time ·", substring = true) and hasClickAction()).performClick()
        pressDialogButton("android:id/button1")
        compose.runOnIdle { assertEquals(LocalTime.of(21, 30), time) }
        compose.onNodeWithText("Clear Do date").performClick()
        compose.runOnIdle { assertEquals("", date) }
    }
}
