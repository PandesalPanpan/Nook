# Nook v0.2.3 release verification

| Release date | Git tag | Version name / code | Package |
| --- | --- | --- | --- |
| 2026-10-02 | `v0.2.3` | `0.2.3` / `5` | `app.nook` |

Brand implementation commit: `9d71ef758ce7ed42daaadf8f570a01688a2ff255`; release version bump commit: `1e6b8aa12063adc180840c950be7fdd80beaac66`.

## Published artifacts

- GitHub Release: https://github.com/PandesalPanpan/Nook/releases/tag/v0.2.3
- APK: `nook-v0.2.3.apk`, 16,839,028 bytes, SHA-256 `b442ac4657432443e35afd749c9468e86c0d1d1e4e6a426806bcdd3043e1066c`
- The release also publishes `update.json` (SHA-256 `1379c233e01bb02198fdef576ce4b3e0e7a63046c57f4d96104846fb564188fa`). Its public contents identify version `0.2.3`, version code `5`, package `app.nook`, and the matching APK checksum.
- GitHub Actions release run: https://github.com/PandesalPanpan/Nook/actions/runs/36988407085 — completed successfully.

## Update verification

On a Pixel 8 API 36 emulator, the signed v0.2.2 app (version code 4) used **Settings → About → Check for updates**. Nook detected the published v0.2.3 release, downloaded it, and reported that the verified APK was ready. Android's package installer then updated the existing installation.

The installed package remained `app.nook`, changed to version code `5` / version name `0.2.3`, and retained the original `firstInstallTime` (`2026-10-02 08:31:31`) while `lastUpdateTime` advanced. No uninstall or app-data clear was performed. A temporary Inbox capture made before the update remained visible after installation and app relaunch; it was deleted after verification, leaving Inbox clear.

The launcher app drawer displayed the dormouse icon after the update. Nook Settings then reported `Nook 0.2.3` and `Nook is up to date`.

Screenshots:

- `artifacts/branding/mascot/screenshots/android-before-update-v0.2.2.png`
- `artifacts/branding/mascot/screenshots/android-inbox-before-update-v0.2.2.png` (temporary verification capture before updating)
- `artifacts/branding/mascot/screenshots/android-update-available-v0.2.3.png`
- `artifacts/branding/mascot/screenshots/android-update-actions-v0.2.3.png`
- `artifacts/branding/mascot/screenshots/android-update-download-v0.2.3.png`
- `artifacts/branding/mascot/screenshots/android-download-status-v0.2.3.png`
- `artifacts/branding/mascot/screenshots/android-native-installer-v0.2.3.png`
- `artifacts/branding/mascot/screenshots/android-install-progress-v0.2.3.png`
- `artifacts/branding/mascot/screenshots/android-play-protect-scan-v0.2.3.png`
- `artifacts/branding/mascot/screenshots/android-play-protect-result-v0.2.3.png`
- `artifacts/branding/mascot/screenshots/android-installed-dialog-v0.2.3.png`
- `artifacts/branding/mascot/screenshots/android-after-update-v0.2.3.png`
- `artifacts/branding/mascot/screenshots/android-launcher-after-update-v0.2.3.png`
- `artifacts/branding/mascot/screenshots/android-inbox-after-update-v0.2.3.png`

## Build and test results

- `npm run typecheck` — passed.
- `npm run lint` — passed.
- `npm test` — passed: 20 files, 80 tests.
- `npm run build` — passed; the PWA build precached 298 entries.
- `npm run test:e2e` — passed: 14 tests passed, 2 account-dependent tests skipped.
- Android debug unit tests, lint, and debug APK assembly — passed.
- `npm run release:android -- 0.2.3` — passed, including Android release validation and signed APK checks.

## Web hosting status

The web app production build contains the dormouse favicon, Apple touch icon, and PWA icons. This repository has no web hosting target configured, so this release did not publish a live website deployment; the public browser-tab icon changes when the web build is deployed to its host.
