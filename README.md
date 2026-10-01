# Nook

Native Android and React web offline-first second brain. The requested V1 implementation and verification are complete, including the device-feedback fixes. Start with the [delivery handoff](docs/HANDOFF.md), [requirement evidence](docs/ACCEPTANCE.md) and [source comparisons](docs/VISUAL-COMPARISON.md). Original requirements remain in [docs/requirements.txt](docs/requirements.txt).

Tested Android APK: [artifacts/apk/nook-v1-debug.apk](artifacts/apk/nook-v1-debug.apk). Web production output: `apps/web/dist`. Both apps work locally without Firebase or AI; production cloud/OAuth configuration and deployment remain external setup, documented in the handoff.

Run the web app with `npm ci` then `npm run dev`. `npm run build` produces apps/web/dist, including the offline service worker. `npm run test:e2e` tests the production build (build first; install Chromium with `npx playwright install chromium` when needed).

## Verify data foundations

```sh
npm ci
npm run typecheck
npm run lint
npm test
npm run test:rules
```

The rules command needs Java 21+ on PATH and starts a dedicated demo-nook emulator suite. It does not require a production Firebase project.

For Android, set JAVA_HOME to JDK 21 and create apps/android/local.properties with your Android SDK path, then:

```sh
cd apps/android
./gradlew test lint assembleDebug
```

On Windows use gradlew.bat. Generated debug APK: apps/android/app/build/outputs/apk/debug/app-debug.apk. Use `./gradlew connectedDebugAndroidTest` with an Android emulator for capture/process/recreation, shared text and images, reminders, widget rendering/action checks, and native note formatting/preview/link/recreation. The final gate passes 88 JVM tests per variant and 49 executed device tests; eight backend-only tests run through separate configured helpers. Web passes 80 unit tests, 14 offline browser flows, and a configured nine-test rules/SDK plus two-browser-flow gate. Exact results and limitations are in [verification](docs/VERIFICATION.md).

See [architecture](docs/ARCHITECTURE.md) and [Firebase setup](docs/FIREBASE.md). Figma inspection works and the Night source context is preserved locally. Cloud credentials have not been provisioned.
