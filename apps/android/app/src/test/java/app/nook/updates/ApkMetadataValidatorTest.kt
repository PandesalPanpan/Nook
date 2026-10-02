package app.nook.updates

import org.junit.Assert.assertThrows
import org.junit.Test

class ApkMetadataValidatorTest {
    private val info = UpdateInfo(
        versionName = "0.2.0", versionCode = 2, packageName = "app.nook", apk = "nook-v0.2.0.apk",
        sha256 = "a".repeat(64), minimumSdk = 26, publishedAt = "2026-10-02T00:00:00Z",
        releaseUrl = "https://github.com/PandesalPanpan/Nook/releases/tag/v0.2.0", releaseNotes = "",
        downloadUrl = "https://github.com/PandesalPanpan/Nook/releases/download/v0.2.0/nook-v0.2.0.apk", apkSizeBytes = 100
    )
    private val valid = ApkMetadata("app.nook", "0.2.0", 2, "B".repeat(64))

    @Test fun matchingPackageVersionChecksumAndSignerAreAccepted() {
        ApkMetadataValidator.validate(valid, info, 1, "b".repeat(64), "a".repeat(64))
    }

    @Test fun wrongPackageIsRejected() {
        assertThrows(UpdateDataException::class.java) { ApkMetadataValidator.validate(valid.copy(packageName = "com.example.other"), info, 1, "b".repeat(64), "a".repeat(64)) }
    }

    @Test fun wrongManifestVersionOrDowngradeIsRejected() {
        assertThrows(UpdateDataException::class.java) { ApkMetadataValidator.validate(valid.copy(versionCode = 3), info, 1, "b".repeat(64), "a".repeat(64)) }
        assertThrows(UpdateDataException::class.java) { ApkMetadataValidator.validate(valid.copy(versionName = "0.3.0"), info, 1, "b".repeat(64), "a".repeat(64)) }
        assertThrows(UpdateDataException::class.java) { ApkMetadataValidator.validate(valid, info, 2, "b".repeat(64), "a".repeat(64)) }
    }

    @Test fun checksumMismatchIsRejectedBeforeInstall() {
        assertThrows(UpdateDataException::class.java) { ApkMetadataValidator.validate(valid, info, 1, "b".repeat(64), "c".repeat(64)) }
    }

    @Test fun unexpectedSigningCertificateIsRejected() {
        assertThrows(UpdateDataException::class.java) { ApkMetadataValidator.validate(valid.copy(certificateSha256 = "c".repeat(64)), info, 1, "b".repeat(64), "a".repeat(64)) }
    }
}
