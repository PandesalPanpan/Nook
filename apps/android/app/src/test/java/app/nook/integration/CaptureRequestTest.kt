package app.nook.integration

import android.content.Intent
import android.net.Uri
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CaptureRequestTest {
    @Test fun sharedTextAndUrlDoNotRequireOrganization() {
        assertEquals(CaptureRequest("Remember this", "text", emptyList()), captureRequest(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "Remember this")))
        assertEquals("link", captureRequest(Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT, "https://example.com/read")).type)
    }
    @Test fun sharedImageKeepsOriginalUriAndOptionalCaption() {
        val uri = Uri.parse("content://gallery/photo-1")
        val request = captureRequest(Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, uri))
        assertEquals("", request.body); assertEquals("image", request.type); assertEquals(listOf(uri), request.uris)
    }
    @Test fun newTaskShortcutStartsUnorganizedCapture() {
        assertEquals(CaptureRequest("", "task", emptyList()), captureRequest(Intent("app.nook.CAPTURE").putExtra("captureType", "task")))
    }
}
