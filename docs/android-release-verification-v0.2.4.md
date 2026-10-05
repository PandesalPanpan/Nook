# Nook v0.2.4 release verification

| Release date | Git tag | Version name / code | Package |
| --- | --- | --- | --- |
| 2026-10-05 | `v0.2.4` | `0.2.4` / `6` | `app.nook` |

Clarification integration commit: `f2a945796fb0905a48797601046b23192fb7c5e8`. Release version bump commit: `b6ae770b9fcbb491f8de31edea240e669ef22a63`.

## Published artifacts

- [GitHub Release](https://github.com/PandesalPanpan/Nook/releases/tag/v0.2.4)
- [APK download](https://github.com/PandesalPanpan/Nook/releases/download/v0.2.4/nook-v0.2.4.apk) — 16,937,332 bytes; SHA-256 `7181bda5cf7acac515720a9eb8c8fe923581dbb1c921f4a03314601ea023d794`.
- [`update.json`](https://github.com/PandesalPanpan/Nook/releases/download/v0.2.4/update.json) identifies `app.nook`, version `0.2.4`, code `6`, minimum SDK `26`, and the matching APK SHA-256.
- GitHub Actions [release run 37304494965](https://github.com/PandesalPanpan/Nook/actions/runs/37304494965) completed successfully. The public release contains exactly the APK and `update.json`; the unauthenticated latest-release API returns `v0.2.4`.
- The downloaded public APK reports package `app.nook`, version code `6`, version name `0.2.4`, and minimum SDK `26`. Its signer SHA-256 is `24C415811C3D073C84159ADC8D36716F1AF7ED9D4C0BAD1EDF176FFF924228C2`, matching the pinned permanent release certificate. Its checksum matches `update.json`.

## In-place update and data preservation

On the disposable `NookClarificationDisposable` API 36 emulator, the public v0.2.3 APK was installed and a synthetic Inbox capture, “Nook upgrade preservation v024,” was created. Nook detected v0.2.4, downloaded the APK, verified it, and opened Android's installer. This emulator's package verification service rejected the installer attempt with `INSTALL_FAILED_VERIFICATION_FAILURE: Install not allowed for file:///data/app/vmdl…`.

Without uninstalling or clearing app data, `adb install -r` applied the same public, checksum-verified v0.2.4 APK. Android then reported version code `6` / version name `0.2.4`, with `firstInstallTime` unchanged from v0.2.3. After relaunch, the synthetic Inbox capture remained visible. It was deleted after verification, leaving the disposable emulator's Inbox empty. No physical device or user AVD was used.

The post-update Inbox screenshot is retained at `artifacts/branding/mascot/screenshots/android-inbox-after-update-v0.2.4.png`.

## Build and test results

- `npm run typecheck`, `npm run lint`, `npm test` (21 files, 85 tests), and `npm run build` passed. The production PWA build generated 304 precache entries.
- `npm run test:e2e` passed: 16 tests passed, 2 account-dependent tests skipped.
- `npm run test:rules` passed all 10 emulator cases. Production Firestore rules were deployed to `nook-app-e3f48` before the updated clients.
- Android JVM tests passed all 122 tests in both debug and release variants. Android lint, debug assembly, and signed release assembly passed.
- Focused instrumentation passed for `CaptureFlowTest` (3 tests), `AccountSwitchFlowTest`, `CaptureProjectTest`, `DateChoiceTest`, `InboxVisualTest`, `WidgetRenderTest`, and `WidgetFontScaleTest`.
- A broader 69-test legacy instrumentation run was attempted and remains red on old UI assertions for replaced flows plus environment-dependent share, shell, and alarm cases. Those failures are not represented as passing; the release workflow runs unit tests, lint, and both APK builds, not connected instrumentation.

## Web deployment status

The existing Netlify site is `nookmarticio` at [https://nook.marticio.com](https://nook.marticio.com). The standard production deploy returned `Forbidden`; the direct Netlify source-upload service returned `500 Internal Server Error`. Netlify still reports the prior ready deployment (`6abf56b47ed803000802e07d`), and the public HTML references its old JavaScript and CSS asset hashes, so the integrated web app is **not live**.

The previous v0.2.3 verification record identified exhausted account credits as the reason Netlify skipped builds. This attempt did not return more detail than the errors above. The tested web production build remains available at `apps/web/dist`; no alternate host or paid plan was used.
