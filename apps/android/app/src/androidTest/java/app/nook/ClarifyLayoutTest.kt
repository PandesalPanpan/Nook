package app.nook

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ClarifyLayoutTest {
    @get:Rule val compose = createComposeRule()
    @Test fun narrowLargeTextDestinationsRemainVisibleAndClickable() {
        val selected = mutableListOf<String>()
        compose.setContent { NookTheme {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                Box(Modifier.width(200.dp).testTag("destinations")) { ClarifyDestinations { selected.add(it) } }
            }
        } }
        val parent = compose.onNodeWithTag("destinations").fetchSemanticsNode().boundsInRoot
        listOf("Task", "Note", "Project", "Resource", "Archive").forEach { label ->
            val node = compose.onNodeWithText(label)
            node.assertIsDisplayed().assertHasClickAction()
            val bounds = node.fetchSemanticsNode().boundsInRoot
            assertTrue(bounds.left >= parent.left && bounds.right <= parent.right)
            assertTrue(bounds.height >= 48f)
            node.performClick()
        }
        compose.runOnIdle { assertEquals(listOf("task", "note", "project", "resource", "archive"), selected) }
    }
}
