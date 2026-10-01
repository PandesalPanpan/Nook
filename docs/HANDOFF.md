# Nook V1 delivery

The original scope is implemented and verified through the final 2026-10-01 gates. See [requirements](requirements.txt), [acceptance evidence](ACCEPTANCE.md), [visual comparisons](VISUAL-COMPARISON.md) and [exact reports](VERIFICATION.md). Production Firebase/OAuth setup and deployment remain external configuration, as permitted by the specification.

## Implemented

Native Kotlin/Compose Android and a desktop React/TypeScript PWA provide local-only onboarding, quick text/task/link/photo capture, Inbox clarification, Projects, Areas, Resources, reversible Archive, tasks/subtasks, separate do dates and deadlines, recurrence, Markdown notes, original attachments, note links/backlinks, dated daily notes, Calendar agendas, local filtered search, weekly review and contextual dismissible PARA education.

Android includes five Glance widgets, share-to-Nook text/URL/image entry, thought/task/photo launcher shortcuts, real camera and Files input, task/deadline/Inbox reminders and notification actions. The capture sheet keeps Save and Photo above the keyboard; date and time pickers and relative-date shortcuts avoid ISO typing. Clarify can assign a destination project before conversion.

Both applications support offline ZIP export/restore with Markdown, JSON and original files. Web additionally supports multi-file Markdown import. Optional AI Off/OpenAI/DeepSeek/custom adapters keep BYOK credentials local, show proposals and require explicit confirmation. Core capture and organization do not depend on AI or Firebase.

## Structure and architecture

- `apps/android`: native Gradle application, Room schema/migrations, repository, SDK adapters and Android integrations.
- `apps/web`: React/Vite/Dexie PWA, repository, cloud/session workers and desktop screens.
- `packages/schemas`: common conceptual schema, validation and compatibility fixtures.
- `packages/design-tokens`: Night colors, spacing and typography.
- `firebase`: restrictive ownership/schema rules, indexes, emulator tests and Storage CORS template.
- `docs`: requirements, architecture, provider/setup instructions and acceptance records.
- `artifacts/verification`, `artifacts/visual`, `artifacts/apk`: executed results, actual rendered screenshots and installable builds.

Room or Dexie is the immediate source of truth. Mutations atomically commit records, search indexes and a durable coalesced outbox. Network work runs later, with retry/backoff and account/version fences. Guest sign-in merges records and original files without replacing the account. Sign-out stops workers and preserves isolated local namespaces; Android also persists a signed-out fence across process death.

Permanent deletion tombstones beat every live version of the same identity. Ordinary live conflicts compare `updatedAt`, then lexical `clientId`. Clock skew remains a limitation. Archive is reversible; Delete is permanent. Original-file sync checks length/hash, retains originals locally, reports progress and acknowledges only matching metadata/byte revisions. See [architecture](ARCHITECTURE.md) for migrations, recurrence identities, links and reconciliation details.

## Run Android

Requires JDK 21, Android SDK platform 36 and an API 26+ device. Set `JAVA_HOME`, `ANDROID_HOME`, and `apps/android/local.properties` (`sdk.dir=...`). From `apps/android`:

```powershell
.\gradlew.bat test lint assembleDebug
.\gradlew.bat installDebug
.\gradlew.bat connectedDebugAndroidTest
```

On macOS/Linux use `./gradlew`. Open **Nook**, choose **Use Nook without an account**, and capture immediately. Reminders and optional sync are under Library/Settings. The generated APK is `apps/android/app/build/outputs/apk/debug/app-debug.apk`; the final tested copy is [nook-v1-debug.apk](../artifacts/apk/nook-v1-debug.apk), with SHA-256 and counts in [v1-delivery.json](../artifacts/verification/v1-delivery.json). This is a debug build, not a Play Store release/signing deployment.

## Run web

From the repository root:

```powershell
npm ci
npm run dev
```

Production and verification:

```powershell
npm run typecheck
npm run lint
npm test
npm run build
npx playwright install chromium
npm run test:e2e
```

Production output is `apps/web/dist`, including the service worker and local Figma assets/fonts. Serve it over HTTPS or localhost. Load once to install the offline cache; subsequent core flows work without internet. No site deployment has been performed.

## Exact Firebase configuration still required

Local-only mode needs nothing. For production synchronization:

1. Create a dedicated Nook Firebase project and register Android package `app.nook` plus a web app. Enable Email/Password and optionally Google Authentication, Firestore and Storage.
2. Copy `apps/android/nook-firebase.example.json` to `apps/android/app/src/main/assets/nook-firebase.json`. Supply the public `apiKey`, Android `applicationId`, `projectId`, `storageBucket` and optional `googleWebClientId`. For Google sign-in configure the Android signing certificate fingerprints and matching web OAuth client. Rebuild the APK.
3. Copy `apps/web/.env.example` to `apps/web/.env.local` and supply all five public `VITE_FIREBASE_*` application settings. Configure the deployed web origin in Firebase Auth authorized domains and rebuild. Leave emulator routing unset for production.
4. Deploy this repository's Firestore/Storage rules and indexes using that explicit project ID. Enable the Storage-to-Firestore permission needed by the owner/live-record rules. Apply `firebase/storage.cors.example.json` with the actual web origin to the dedicated bucket.

Cloud records use `users/{uid}/records/{id}`; originals use `users/{uid}/attachments/{attachmentId}/original`. No unrelated Firebase project was reused, and no production rules, OAuth or service were deployed. Public config is not an AI API key; BYOK credentials remain device/browser local. See [Firebase](FIREBASE.md) and [AI](AI.md).

Demo-only rule/backend verification needs Java 21 on PATH:

```powershell
npm run test:rules
npx firebase emulators:exec --project demo-nook --only auth,firestore,storage "powershell -NoProfile -ExecutionPolicy Bypass -File scripts/test-web-backend.ps1"
```

Configured native SDK/account/cold-entry helpers are documented in `docs/FIREBASE.md` and `scripts/test-native-*.ps1`. They restore temporary configuration and device networking.

## Verified results

- Clean locked npm installation; typecheck/lint, 80 unit tests and production PWA build pass. Default browser gate passes 14 offline flows; two dedicated backend flows are intentionally skipped there.
- Latest configured backend gate passes nine rules/SDK tests and both account/original-file production browser flows: `artifacts/verification/web-final-configured.txt`.
- Native `test lint assembleDebug connectedDebugAndroidTest` passes: 88 JVM tests per debug/release variant; 57 connected entries, 49 executed, eight opt-in backend skips, zero failures/errors. Lint has zero errors, 56 warnings and one hint. See `native-acceptance-final.txt/xml`. The post-gate capture/Inbox refresh passes two production flows; the broader production/system screenshot gate passes eight flows.
- Real Android Files/camera originals, Sharesheet, launcher intents, widgets and reminders have exercised device tests. Authenticated process-death/offline/sign-out checks and automatic 4 MiB original transfer have passing configured evidence. Screenshots are real source comparisons, including narrow/large-text layouts and 20 widget configurations.

## Limits and V2

Production Firebase/Google OAuth and live billable AI requests require external configuration and have not been exercised. Provider adapters are tested against controlled HTTP responses; quality of live generated suggestions is not claimed. Custom browser AI endpoints need HTTPS/CORS.

WorkManager reminders can be delayed by Android battery/network policy. Widget appearance depends on launcher dimensions. Verification covers the API 36 emulator, normal/200% fonts and named web viewports, not every manufacturer, keyboard or assistive technology. Performance measurements are specific debug/emulator samples, not universal hardware guarantees. Attachments are bounded at 20 files and 50 MB combined per capture/add operation; backup restore is bounded at 250 MB. Development Firebase tooling has three moderate dependency advisories; the production dependency audit has none. Existing lint warnings are advisory and documented rather than hidden.

Free-form natural-language date parsing, optional AI natural-language retrieval, a native bulk Markdown-folder picker, server push and a full external calendar replacement are not implemented. The requested date usability is supplied through one-tap defaults and native pickers. Production release signing, store publication and web hosting remain separate deployment work.
