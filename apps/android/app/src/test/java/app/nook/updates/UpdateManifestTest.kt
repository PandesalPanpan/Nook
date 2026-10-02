package app.nook.updates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class UpdateManifestTest {
    private val valid = """{
      "schemaVersion": 1,
      "versionName": "0.2.0",
      "versionCode": 2,
      "packageName": "app.nook",
      "apk": "nook-v0.2.0.apk",
      "sha256": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
      "minimumSdk": 26,
      "publishedAt": "2026-10-02T00:00:00Z",
      "releaseUrl": "https://github.com/PandesalPanpan/Nook/releases/tag/v0.2.0"
    }"""

    @Test fun validManifestParses() {
        val result = UpdateManifestParser.parse(valid)
        assertEquals(1, result.schemaVersion)
        assertEquals(2L, result.versionCode)
        assertEquals("app.nook", result.packageName)
    }

    @Test fun malformedJsonIsRejected() {
        assertThrows(UpdateDataException::class.java) { UpdateManifestParser.parse("{broken") }
    }

    @Test fun missingRequiredFieldsAreRejected() {
        val missing = valid.replace(Regex("\\s*\"minimumSdk\"[^,]+,?"), "")
        assertThrows(UpdateDataException::class.java) { UpdateManifestParser.parse(missing) }
    }

    @Test fun unsupportedSchemaIsRejected() {
        assertThrows(UpdateDataException::class.java) { UpdateManifestParser.parse(valid.replace("\"schemaVersion\": 1", "\"schemaVersion\": 2")) }
    }

    @Test fun wrongPackageIsRejected() {
        assertThrows(UpdateDataException::class.java) { UpdateManifestParser.parse(valid.replace("app.nook", "com.example.other")) }
    }

    @Test fun invalidChecksumIsRejected() {
        assertThrows(UpdateDataException::class.java) { UpdateManifestParser.parse(valid.replace(Regex("0123456789abcdef.+?\""), "invalid\"")) }
    }

    @Test fun unsafeApkNamesAndUrlsAreRejected() {
        assertThrows(UpdateDataException::class.java) { UpdateManifestParser.parse(valid.replace("nook-v0.2.0.apk", "../nook-v0.2.0.apk")) }
        assertThrows(UpdateDataException::class.java) { UpdateManifestParser.parse(valid.replace("https://github.com/PandesalPanpan/Nook/releases/tag/v0.2.0", "http://github.com/PandesalPanpan/Nook/releases/tag/v0.2.0")) }
        assertThrows(UpdateDataException::class.java) { UpdateManifestParser.parse(valid.replace("https://github.com/PandesalPanpan/Nook/releases/tag/v0.2.0", "https://evil.example/releases/tag/v0.2.0")) }
    }
}
