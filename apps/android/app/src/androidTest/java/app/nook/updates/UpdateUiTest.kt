package app.nook.updates

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.nook.NookTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class UpdateUiTest {
    @get:Rule val compose = createComposeRule()

    private val info = UpdateInfo(
        versionName = "0.2.0", versionCode = 2, packageName = "app.nook", apk = "nook-v0.2.0.apk",
        sha256 = "a".repeat(64), minimumSdk = 26, publishedAt = "2026-10-02T00:00:00Z",
        releaseUrl = "https://github.com/PandesalPanpan/Nook/releases/tag/v0.2.0", releaseNotes = "Safer updates and release fixes.",
        downloadUrl = "https://github.com/PandesalPanpan/Nook/releases/download/v0.2.0/nook-v0.2.0.apk", apkSizeBytes = 100
    )

    @Test fun availableCardShowsReleaseNotesAndLater() {
        var later = false
        var update = false
        compose.setContent {
            NookTheme {
                UpdateBanner(
                    UpdateUiState(UpdateStatus.AVAILABLE, InstalledVersion("0.1.0", 1), info),
                    onLater = { later = true }, onDownload = { update = true }, onCancel = {}, onInstall = {}, onRetryCheck = {}
                )
            }
        }
        compose.onNodeWithText("Update available").assertIsDisplayed()
        compose.onNodeWithText("Nook 0.2.0").assertIsDisplayed()
        compose.onNodeWithText("Safer updates and release fixes.").assertIsDisplayed()
        compose.onNodeWithText("Current: 0.1.0 · Available: 0.2.0").assertIsDisplayed()
        compose.onNodeWithText("Later").performClick()
        assertTrue(later)
        compose.onNodeWithText("Update now").performClick()
        assertTrue(update)
    }

    @Test fun downloadShowsProgressAndCanBeCancelled() {
        var cancelled = false
        compose.setContent {
            NookTheme {
                UpdateBanner(
                    UpdateUiState(UpdateStatus.DOWNLOADING, InstalledVersion("0.1.0", 1), info, downloadedBytes = 72, totalBytes = 100),
                    onLater = {}, onDownload = {}, onCancel = { cancelled = true }, onInstall = {}, onRetryCheck = {}
                )
            }
        }
        compose.onNodeWithText("Downloading Nook 0.2.0").assertIsDisplayed()
        compose.onNodeWithText("72%").assertIsDisplayed()
        compose.onNodeWithText("Cancel download").performClick()
        assertTrue(cancelled)
    }

    @Test fun downloadErrorOffersRetry() {
        var retry = false
        compose.setContent {
            NookTheme {
                UpdateBanner(
                    UpdateUiState(UpdateStatus.ERROR, InstalledVersion("0.1.0", 1), info, failureStage = UpdateFailureStage.DOWNLOAD, message = "Checksum failed."),
                    onLater = {}, onDownload = { retry = true }, onCancel = {}, onInstall = {}, onRetryCheck = {}
                )
            }
        }
        compose.onNodeWithText("Checksum failed.").assertIsDisplayed()
        compose.onNodeWithText("Retry download").performClick()
        assertTrue(retry)

    }

    @Test fun upToDateSettingsOffersManualCheck() {
        var manualCheck = false
        compose.setContent {
            NookTheme {
                AboutUpdatesCard(UpdateUiState(UpdateStatus.UP_TO_DATE, InstalledVersion("0.2.0", 2)), onCheck = { manualCheck = true }, onDownload = {}, onCancel = {}, onInstall = {})
            }
        }
        compose.onNodeWithText("Version 0.2.0").assertIsDisplayed()
        compose.onNodeWithText("Nook is up to date.").assertIsDisplayed()
        compose.onNodeWithText("Check for updates").performClick()
        assertTrue(manualCheck)
    }
}
