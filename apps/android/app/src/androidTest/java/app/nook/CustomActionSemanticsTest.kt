package app.nook

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.nook.data.NookDatabase
import app.nook.data.NookRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class CustomActionSemanticsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun captureAndAgendaRowsExposeNamedButtonsWithoutMakingInformationalRowsActions(): Unit = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), NookDatabase::class.java).build()
        val repo = NookRepository(db, "local:semantics", "client")
        try {
            val capture = repo.capture("A thought to clarify later")
            var opened = ""
            var agendaOpened = 0
            compose.setContent { NookTheme {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    InboxContent(repo, listOf(capture), { opened = it.id }, {})
                    CalendarAgendaRow("Anytime", "Prepare project notes", "Do date") { agendaOpened++ }
                    CalendarAgendaRow("10:00 AM", "Inbox review", "Scheduled reminder")
                }
            } }
            for (title in listOf("A thought to clarify later", "Prepare project notes")) {
                compose.onNodeWithText(title).performScrollTo().assertIsDisplayed()
                    .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
                    .assertHasClickAction().assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
                    .performClick()
            }
            compose.onNodeWithText("Inbox review").performScrollTo().assertIsDisplayed()
                .assertHasNoClickAction()
                .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Role))
            compose.runOnIdle {
                assertEquals(capture.id, opened)
                assertEquals(1, agendaOpened)
            }
        } finally { db.close() }
    }
}
