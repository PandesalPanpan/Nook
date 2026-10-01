package app.nook

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import kotlin.math.pow

class ControlContrastTest {
    @get:Rule val compose = createComposeRule()
    @Test fun primaryFillAndOrdinaryMaterialTextUseReadableSeparateRoles() {
        var actionForeground = Color.Unspecified
        var textForeground = Color.Unspecified
        var focusedLabel = Color.Unspecified
        var background = Color.Unspecified
        var surface = Color.Unspecified
        var elevated = Color.Unspecified
        compose.setContent { NookTheme { Column {
            background = MaterialTheme.colorScheme.background
            surface = MaterialTheme.colorScheme.surface
            elevated = MaterialTheme.colorScheme.surfaceVariant
            focusedLabel = OutlinedTextFieldDefaults.colors().focusedLabelColor
            NookButton(onClick = {}) { actionForeground = LocalContentColor.current; Text("Save") }
            TextButton(onClick = {}) { textForeground = LocalContentColor.current; Text("Manage") }
        } } }
        compose.onNodeWithText("Save").assertHasClickAction().assertIsDisplayed()
        compose.onNodeWithText("Manage").assertHasClickAction().assertIsDisplayed()
        fun luminance(color: Color): Double {
            fun linear(value: Float) = if(value <= 0.04045f) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
            return .2126 * linear(color.red) + .7152 * linear(color.green) + .0722 * linear(color.blue)
        }
        fun contrast(a: Color, b: Color): Double {
            val first = luminance(a); val second = luminance(b)
            return (maxOf(first, second) + .05) / (minOf(first, second) + .05)
        }
        compose.runOnIdle {
            assertTrue("Ordinary action label on original blue", contrast(actionForeground, NookActionBlue) >= 4.5)
            for(container in listOf(background, surface, elevated)) {
                assertTrue("Material text action on a Night surface", contrast(textForeground, container) >= 4.5)
                assertTrue("Focused field label on a Night surface", contrast(focusedLabel, container) >= 4.5)
            }
        }
    }
}
