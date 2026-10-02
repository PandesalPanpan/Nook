# Android releases and in-app updates

Nook is distributed as a signed APK attached to a public GitHub Release. The Android updater uses GitHub without a token. Update checking is optional and never gates startup, Room data, sync, capture, reminders, widgets, or offline use.

## Release architecture

Each release contains exactly one `nook-v<VERSION>.apk` and one `update.json` asset. The app calls:

```text
https://api.github.com/repos/PandesalPanpan/Nook/releases/latest
```

GitHub's latest-release endpoint excludes drafts and prereleases. Nook then requires exactly one `update.json` and one APK asset with the versioned filename. The manifest's numeric `versionCode` determines whether an update is available; `versionName` is for display and tag validation only.

Manifest schema version 1:

```json
{
  "schemaVersion": 1,
  "versionName": "0.2.0",
  "versionCode": 2,
  "packageName": "app.nook",
  "apk": "nook-v0.2.0.apk",
  "sha256": "<64 lowercase hexadecimal characters>",
  "minimumSdk": 26,
  "publishedAt": "2026-10-02T00:00:00Z",
  "releaseUrl": "https://github.com/PandesalPanpan/Nook/releases/tag/v0.2.0"
}
```

The release workflow generates this file from Gradle metadata and the finished APK checksum. It validates the APK's package, version name, version code, minimum SDK, and signing certificate before publishing.

## Signing identity

The permanent release key is a 3072-bit RSA PKCS#12 keystore with alias `nook-release`, valid for 10,000 days. The local key is stored outside the repository at:

```text
%USERPROFILE%\.nook\signing\nook-release.p12
```

The matching password is protected with Windows DPAPI for the current Windows account. Never commit or email the keystore or its password. Keep a secure backup of the keystore and DPAPI password recovery material. GitHub Actions stores its base64 encoding and signing inputs as repository Actions secrets; GitHub will not reveal secret values later, so losing the local keystore means losing the ability to sign compatible updates.

The public signer fingerprint is in [release-certificate.sha256](../apps/android/release-certificate.sha256) and is checked by the app and CI. The Firebase Android app is `app.nook` in project `nook-app-e3f48`.

Local `assembleRelease` requires these environment variables and fails if any are missing:

- `NOOK_RELEASE_KEYSTORE_PATH`
- `NOOK_RELEASE_STORE_PASSWORD`
- `NOOK_RELEASE_KEY_ALIAS`
- `NOOK_RELEASE_KEY_PASSWORD`

The Gradle file reads them only from the environment. The GitHub workflow decodes the encrypted Actions keystore secret to the runner's temporary directory and removes the temporary key and Firebase config during cleanup.

## Firebase CI configuration and Actions secrets

`apps/android/app/src/main/assets/nook-firebase.json` is ignored by Git. It contains Firebase Android client configuration used by the existing native loader; it is not a service-account credential. The workflow requires its base64 contents as `NOOK_FIREBASE_CONFIG_BASE64` and checks that the project is `nook-app-e3f48` and Google client ID is present.

After authenticating GitHub CLI with admin access to `PandesalPanpan/Nook`, configure or refresh all five Actions secrets from the local config, DPAPI-protected password, and keystore:

```powershell
gh auth status
.\scripts\set-android-release-secrets.ps1
gh secret list --repo PandesalPanpan/Nook --app actions
```

The secret names are:

- `NOOK_FIREBASE_CONFIG_BASE64`
- `NOOK_RELEASE_KEYSTORE_BASE64`
- `NOOK_RELEASE_STORE_PASSWORD`
- `NOOK_RELEASE_KEY_ALIAS`
- `NOOK_RELEASE_KEY_PASSWORD`

The app contains no GitHub token. GitHub Actions uses its short-lived `GITHUB_TOKEN` only to publish the release.

## Automatic and manual checks

The foreground app schedules a network-constrained WorkManager check every six hours and performs a throttled check on launch/resume. Successful check data is cached locally. Failed automatic checks stay quiet and retry later; a manual check always goes to GitHub. The app can still open and operate offline if GitHub, DNS, or the network is unavailable.

Open **Settings → About Nook → Check for updates** for a fresh check. An available version appears in an inline card with release notes, **Later**, and **Update now**. Downloads report progress and can be cancelled. A completed APK must pass all of these checks before the installer opens:

1. SHA-256 equals the manifest value.
2. The file is a readable APK for package `app.nook`.
3. Its version name and numeric version code match the manifest, and its code is higher than the installed app.
4. Its APK signer SHA-256 equals the pinned certificate.

Downloads use the app cache and AndroidX FileProvider `content://` URIs. Android displays its normal installer confirmation. On Android 8 or newer, Nook may ask you to allow installs from Nook in the app-specific **Install unknown apps** settings. Return to Nook and tap **Install update** again after changing that setting.

## Cutting a release

1. Commit and push the intended Android changes and non-empty release notes to `main` at `docs/release-notes/v<VERSION>.md`.
2. Ensure `main` is clean and matches `origin/main`. Configure `JAVA_HOME` to JDK 21, put its `bin` directory first on `PATH`, and ensure Android SDK platform 36 is installed locally.
3. Run:

   ```powershell
   npm run release:android -- 0.3.0
   ```

4. The helper rejects a dirty/non-main/out-of-date branch and existing tags. It increments `versionCode`, updates `versionName`, runs Android unit tests and lint, assembles debug and signed release APKs, checks the package/version/signature, commits the version change, creates `v<VERSION>`, and pushes `main` plus the tag.
5. The tag starts `.github/workflows/android-release.yml`. Check `gh run list --repo PandesalPanpan/Nook` and the run logs. The workflow publishes the signed APK and `update.json` only after every check passes.

If a tag was created before the workflow was available, or its automatic run failed before a job started, open **Actions → Publish Android release → Run workflow** on `main`, enter the existing tag (for example `v0.2.0`), and run it. The workflow checks out and validates the tagged commit; it does not move or replace the tag.

The first production release is `v0.2.0` with `versionCode = 2`. Future `versionCode` values increase by one; never reuse a code or overwrite a tag. Keep each release notes file in the source commit.

## Debug to release migration

The old development APK may be signed with Android's debug key. Android will reject a permanent release-signed APK as an in-place update if those signer certificates differ. If this happens for the first production install, export a Nook backup, uninstall the debug build once, and install the signed `v0.2.0` APK. Uninstalling an Android app removes its on-device Room data, so do not perform this migration on the device holding the only copy of important local-only data. Once the signed release is installed, all future Nook releases retain this certificate and can update normally without uninstalling.

## Firebase Google Sign-In certificate

The permanent release SHA-1 and SHA-256 fingerprints have been added to the Firebase Android app for `app.nook` in project `nook-app-e3f48`; the existing debug fingerprints remain. Google Sign-In uses the configured web OAuth client ID. If sign-in fails in a newly installed release, verify the Google provider and web client ID in the Firebase project and allow time for configuration propagation.

## Troubleshooting and recovery

- **Check fails:** confirm the repository is public, the device has a network connection, and the latest published release contains exactly one APK and one `update.json`. Manual checks are not throttled.
- **Download fails:** retry from the update card. Partial downloads are removed. The updater rejects non-GitHub URLs, oversized files, checksum mismatches, wrong packages/versions, and unexpected signing certificates.
- **Install button opens settings:** enable Nook under Android's app-specific Install unknown apps page, return, then tap Install update again.
- **Android reports a signature conflict:** the installed release was not signed by this permanent certificate. Do not delete Nook data casually. Export it first, then install the signed release after removing the incompatible debug build.
- **Actions reports a missing secret:** run `scripts/set-android-release-secrets.ps1` from an authenticated Windows account with the local Firebase config and original keystore present.
- **Signing key recovery:** restore the original `nook-release.p12` and its password to `%USERPROFILE%\.nook\signing`; refresh the corresponding Actions secrets with the helper. GitHub secrets cannot be read back. If the private key is lost or compromised, new-key APKs cannot update existing installs in place; users need a documented migration after backing up data.
- **Firebase Auth fails only in release builds:** compare the APK signer SHA-1/SHA-256 with the entries on the Firebase Android app and verify that the release build loaded the `nook-app-e3f48` client config.

## Verification scope

The release workflow runs Android unit tests, lint, `assembleDebug`, and `assembleRelease`; verifies the APK signer and metadata; then generates and publishes release metadata. The updater's pure validation, transport, and policy tests live under `apps/android/app/src/test/java/app/nook/updates`; UI behavior is covered under `src/androidTest`.

### Published releases and in-place upgrade check — 2026-10-02

- Public releases `v0.2.0` and `v0.2.1` completed their GitHub Actions workflows successfully ([v0.2.0 run](https://github.com/PandesalPanpan/Nook/actions/runs/36976384689), [v0.2.1 run](https://github.com/PandesalPanpan/Nook/actions/runs/36977557356)). Each release has its versioned APK and `update.json`. The v0.2.1 APK is `nook-v0.2.1.apk`, version code 3, SHA-256 `e4e342baf528d617eb66610ae20da44a8c7d96f3cdffe37c651e065b60c0d687`.
- The latest local release build report has 118 JVM tests in each of the debug and release variants, with no failures, errors, or skips. The release workflow also completed lint and both APK builds.
- The actual v0.2.0 → v0.2.1 in-app update was completed on the disposable `NookReleaseDisposable` Android emulator. Nook's manual check found v0.2.1, downloaded it, verified it, opened Android's normal installer, and completed the update. After reopening, Android reported version code 3 / version name 0.2.1, Nook reported no update available, and a synthetic Inbox capture created before the update was still present. The app-specific unknown-apps setting was enabled only on that disposable emulator. No physical phone was used.
- The Firebase client config and permanent release certificate fingerprints are configured for release builds. Live Google Sign-In and email/password authentication were not exercised in this pass; no test account was used.

### v0.2.2 — mascot release and in-place upgrade — 2026-10-02

- The tag-triggered [GitHub Actions run](https://github.com/PandesalPanpan/Nook/actions/runs/36983424903) succeeded. The public [v0.2.2 release](https://github.com/PandesalPanpan/Nook/releases/tag/v0.2.2) is the latest stable release and contains exactly `nook-v0.2.2.apk` and `update.json`.
- The public latest-release API and `update.json` report version name 0.2.2, version code 4, and package `app.nook`. The published APK SHA-256 is `a83d97b7552630b59dcc03d084259527031e6ec12c53eeba1ae29d0a52058782`, matching the manifest. The APK signer matches the pinned permanent certificate `24C415811C3D073C84159ADC8D36716F1AF7ED9D4C0BAD1EDF176FFF924228C2`.
- The v0.2.1 → v0.2.2 in-app update was completed on the existing disposable Pixel 8 API 36 emulator. Nook's manual updater found v0.2.2, downloaded and verified the release APK, then opened Android's installer. Play Protect scanned it and reported it safe; installation completed without uninstalling. Android then reported version code 4 / version name 0.2.2, the install-time record was unchanged, and the existing Room Inbox capture “Nook updater preservation check” remained visible. No physical phone was used.
- The published Android welcome screen was also inspected from the v0.2.2 APK on a separate clean Pixel 8 API 36 emulator; the screenshot is retained under `artifacts/branding/mascot/screenshots/android-welcome.png`.
