package app.nook.updates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LatestReleaseParserTest {
    private val apkUrl = "https://github.com/PandesalPanpan/Nook/releases/download/v0.2.0/nook-v0.2.0.apk"
    private val manifestUrl = "https://github.com/PandesalPanpan/Nook/releases/download/v0.2.0/update.json"

    private fun payload(
        draft: Boolean = false,
        prerelease: Boolean = false,
        assets: String = """{"name":"nook-v0.2.0.apk","browser_download_url":"$apkUrl","size":1024,"state":"uploaded"},{"name":"update.json","browser_download_url":"$manifestUrl","size":240,"state":"uploaded"}"""
    ) = """{
      "draft": $draft,
      "prerelease": $prerelease,
      "tag_name": "v0.2.0",
      "published_at": "2026-10-02T00:00:00Z",
      "html_url": "https://github.com/PandesalPanpan/Nook/releases/tag/v0.2.0",
      "body": "Nook update notes",
      "assets": [$assets]
    }"""

    @Test fun selectsExactlyTheVersionedApkAndManifest() {
        val release = LatestReleaseParser.parse(payload())
        assertEquals("v0.2.0", release.tagName)
        assertEquals("nook-v0.2.0.apk", release.apk.name)
        assertEquals("update.json", release.updateManifest.name)
        assertEquals("Nook update notes", release.releaseNotes)
    }

    @Test fun draftAndPrereleaseAreRejected() {
        assertThrows(UpdateDataException::class.java) { LatestReleaseParser.parse(payload(draft = true)) }
        assertThrows(UpdateDataException::class.java) { LatestReleaseParser.parse(payload(prerelease = true)) }
    }

    @Test fun missingOrDuplicateUpdateManifestIsRejected() {
        val apkOnly = """{"name":"nook-v0.2.0.apk","browser_download_url":"$apkUrl","size":1024,"state":"uploaded"}"""
        val oneManifest = """{"name":"update.json","browser_download_url":"$manifestUrl","size":240,"state":"uploaded"}"""
        assertThrows(UpdateDataException::class.java) { LatestReleaseParser.parse(payload(assets = apkOnly)) }
        assertThrows(UpdateDataException::class.java) { LatestReleaseParser.parse(payload(assets = "$apkOnly,$oneManifest,$oneManifest")) }
    }

    @Test fun missingApkAndMaliciousAssetUrlAreRejected() {
        val manifestOnly = """{"name":"update.json","browser_download_url":"$manifestUrl","size":240,"state":"uploaded"}"""
        val malicious = """{"name":"nook-v0.2.0.apk","browser_download_url":"https://evil.example/nook-v0.2.0.apk","size":1024,"state":"uploaded"},$manifestOnly"""
        assertThrows(UpdateDataException::class.java) { LatestReleaseParser.parse(payload(assets = manifestOnly)) }
        assertThrows(UpdateDataException::class.java) { LatestReleaseParser.parse(payload(assets = malicious)) }
    }

    @Test fun invalidTagOrReleaseUrlIsRejected() {
        assertThrows(UpdateDataException::class.java) { LatestReleaseParser.parse(payload().replace("v0.2.0", "release-0.2.0")) }
        assertThrows(UpdateDataException::class.java) { LatestReleaseParser.parse(payload().replace("https://github.com/PandesalPanpan/Nook/releases/tag/v0.2.0", "https://example.com/release")) }
    }

    @Test fun assetRedirectsRequireAnAllowedHttpsHostAndStandardPort() {
        assertFalse(UpdateUrls.isAllowedAssetRedirect("http://release-assets.githubusercontent.com/file"))
        assertFalse(UpdateUrls.isAllowedAssetRedirect("https://release-assets.githubusercontent.com:8443/file"))
        assertFalse(UpdateUrls.isAllowedAssetRedirect("https://evil.example/file"))
        assertTrue(UpdateUrls.isAllowedAssetRedirect("https://release-assets.githubusercontent.com/file?token=temporary"))
    }
}
