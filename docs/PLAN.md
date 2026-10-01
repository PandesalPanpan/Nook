# Current status — V1 delivery verified on 2026-10-01

The original scope is implemented and the final gates/source comparisons pass. See HANDOFF.md, ACCEPTANCE.md and VISUAL-COMPARISON.md for the final decision and exact limitations. The entries below preserve historical execution; older pending labels do not supersede the current acceptance record.

# Nook implementation and verification



The full specification is preserved in requirements.txt. Completion requires every item there, including native Android, cloud sync, visual comparison and offline verification.



- [x] Inspect workspace (empty) and preserve specification.

- [x] Inspect Figma Night tokens and representative mobile/desktop frames. Read-only context is preserved in docs/figma; remaining Android system/setup frames need detailed inspection during implementation.

- [x] Establish shared schema and transactional local-first repositories on Dexie and Room.

- [ ] Complete capture → Inbox → process to Task/Note/Project on both platforms.

- [ ] Expand onboarding, Today, PARA, editor, daily notes, calendar, search, review and settings.

- [ ] Implement outbox transport, auth merge, isolation, tombstones, conflict handling and emulator tests.

- [ ] Add attachments, ZIP export/restore and optional device-local AI providers.

- [ ] Add Android widgets, share surface, shortcuts and reminders.

- [ ] Verify web typecheck/lint/tests/build and Android test/lint/debug APK.

- [ ] Verify offline flows and compare all specified representative screens with Figma.

- [ ] Produce final run/setup documentation and artifacts.



No Firebase project has been selected or provisioned. Do not reuse another application's credentials.



Implemented foundations (not complete UI features):



- Dexie and Room capture processing, archive/deletion semantics, durable outboxes, account-key isolation and guest migration.

- Sync engines with version-aware acknowledgement, retry/backoff and stop behavior; web and native Firebase transaction transports.

- Restrictive cloud rules, demo emulator configuration and nine passing emulator tests, including real web transport reconnect/merge/deletion.

- Web and Android original-file capture and atomic ZIP restore/export with Markdown and JSON. Native photo picker, backup document picker and original-file save actions are connected. Product sync uploads/downloads original bytes with size/SHA-256 checks and tombstone cleanup. Full transfer-status UX and performance acceptance remain pending.

- Android Gradle wrapper, launchable Compose Activity, local onboarding, Today, Inbox processing, task/record editing, subtasks, search and archive. Repository and sync tests run against Room with Robolectric; capture-to-task and activity recreation pass an emulator UI test with Wi-Fi and mobile data disabled.

- React desktop UI and offline PWA build using actual Figma tokens and local assets. Two browser tests verify keyboard capture and the offline capture/edit/complete/archive/search/delete/reopen flow. Project/Note/Calendar layout comparisons and complete feature behavior remain pending.

- Indexed offline search on both platforms, with project/area/before filters and transactional index maintenance. Native migration backfills FTS and retains existing data.

- Android text/URL/image share capture, three launcher shortcuts, five Glance widgets, task/deadline reminders and daily Inbox review with Capture/Process/Later actions. Emulator tests cover share persistence, notification delivery, empty-Inbox suppression, widget rendering and account-safe task completion. Original system-frame assets and compact widget layouts are implemented; final measured visual acceptance remains pending.

- Daily/weekly/monthly recurring tasks on both platforms, with repeat controls, preserved completion history, independent do date/deadline advancement, recurring reminders and project next-action advancement. Domain tests cover month-end/leap-year behavior, atomic rejection, deletion and duplicate prevention; browser and native emulator flows verify configuration/completion/reopening offline.

- Selection-aware Markdown formatting, native/web preview, clickable wiki links and parsed backlinks. Native has a searchable Link note picker. Existing records accept original attachments directly with atomic owner/file metadata; typed-title links currently resolve against current titles, while picker links use stable IDs. Full Note Editor Figma layout comparison remains pending.

- Optional dated daily notes open from Calendar on both platforms, reuse account-scoped daily identities, preserve journal/related-item edits and index locally. Deleted generations remain tombstoned. Calendar includes local-time reminders alongside separate do dates, deadlines and project targets. Daily Note source frame 2:707 was inspected and preserved; measured final Daily/Calendar layout fidelity remains pending.

- Four-part weekly review on both platforms with account/week-scoped draft recovery, record review links and an idempotent saved weekly focus note. Native recreation and web reload/revision flows pass offline. Mobile 2:779 and web 5:183 source frames were inspected. Web uses the original 42 px step SVGs and a two-column review layout; final whole-screen visual acceptance remains pending.

- Verification details and remaining gates are recorded in VERIFICATION.md.



- Web account-session coordinator now serializes namespace changes, stops old sync workers, preserves pending cloud operations on sign-out, and merges guest data only during explicit guest sign-in. Six domain tests cover account switching and failure recovery. Firebase Auth and bootstrap are connected; settings controls are connected and the email account browser flow passes against emulators.



- Firebase Auth adapter and authentication-session lifecycle are implemented and emulator-tested for registration, guest upload, sign-out and two-account switching. Restored sessions do not implicitly merge guest data. Configured web bootstrap restores the authenticated namespace; repository context and account-keyed remounts reset navigation, live queries, editors and capture dialogs on changes. Local-only bootstrap does not initialize Firebase. Automatic sync scheduling and prompt cancellation of pending SDK waits are implemented and tested. Account settings controls are connected and the email account browser flow passes against emulators.



- Native account/session/bootstrap serializes Auth changes and worker shutdown, restores cached identity without implicit guest import, commits stable preferences before repository publication and remounts editors/navigation on account changes. Room tests cover safe explicit merge, account isolation, cancellation, failures and queued-operation preservation. Credential changes await tagged WorkManager cancellation; connected tests verify unrelated work survives and queued/periodic sync requests stay bounded. SyncWorker uses the shared Room instance and stable client ID. Missing configuration skips Firebase. Share/widget/reminder entry points restore cached Auth before selecting a repository. Namespace changes clear previous reminder work and posted notifications, then refresh system surfaces.



- Native Firebase SDK integration now passes a dedicated Auth/Firestore/Storage emulator test: guest registration/upload, second-client reads and archive updates, permanent deletion winning over stale writes, original byte preservation and cross-account file denial, and sign-out isolation. The standard suite passes ten offline flows with this opt-in test skipped. Native test/lint/APK checks pass; debug emulator hosts explicitly exclude subdomains. Configured native bootstrap/UI coverage remains pending.



- Native Settings → Sync now offers email sign-in/registration, Google Credential Manager entry, actual queued-record rows, manual sync and disconnect. Process-owned account actions survive UI remounts; transient password fields are not saved and clear on submission. A configured native UI emulator flow passes failed sign-in preservation, guest registration/merge/upload, disconnect isolation, returning sign-in and activity recreation. Google domain tests cover safe merge and picker dismissal; production OAuth remains unverified. The optional setup and outbox components use source frames 4:92 and 2:940 with original 42/32 px SVGs. Final whole-screen visual acceptance remains pending.



- Native automatic scheduling observes committed outbox payloads, ignores retry-only changes, coalesces WorkManager requests and retries scheduler failures. It pulls on startup and every 30 seconds while visible, with network-constrained persistent 15-minute background work. Account transitions stop/join both observer and foreground timer before credential cancellation. Four domain tests cover local-only inactivity, commit scheduling, retry exclusion, cancellation, retry recovery and foreground lifecycle. The expanded real-emulator UI gate starts through Share to Nook, verifies automatic registration upload and offline/reconnect catch-up, and confirms disconnect clears posted/deferred reminders. Broader authenticated widget/reminder process-restart coverage remains pending.



Optional AI now has separate provider and confirmed-proposal layers on both platforms, local account-scoped BYOK settings, controlled-response tests and real browser/Compose confirmation flows. Details and remaining external/live verification are in AI.md.



Next: complete authenticated widget/reminder restart coverage; finish remaining associations and measured Project/Note/Calendar layouts. Audit attachment transfer status, large-file/retry performance, AI/settings fidelity and whole-product accessibility. Figma access is working after correcting the connector account identifier. The full goal remains unfinished.



Contextual learning now includes dismissible PARA tip families, the optional four-place guide from frame 4:138, and global suppression/reset on both platforms. Atomic AppSettings edits preserve other preferences and enqueue locally; controlled domain/UI tests cover account isolation, backups, offline reopening and confirmation of dismissals. Whole-product acceptance still remains.


Record contexts now have native Project/Area/Resource selectors and next-action selection, native Area project/task lists, and contextual note creation/lists. Web note contexts, next-action selection and Area-related work are connected. Task creation carries parent context. Native editors refresh only pristine drafts when records change; dirty-draft and relationship flows are instrumented. Direct Resource-to-Project/Area relationships, effective Area inheritance for existing children, full related-work draft navigation and final measured layouts still need completion.

Direct Resource-to-Project/Area associations are now implemented across the shared/native models, validation, rules, editor controls, related lists and local search. Effective Area resolution supports existing Project-linked children while explicit Area choices win. Legacy and backup compatibility are tested; parent-only subtask context, broader relationship validation, related-work draft navigation and final visual/performance acceptance still need review.

Legacy parent-only subtask Area resolution now handles nested Tasks, cycles, deletions and account fencing, without rewriting records. Queries and related lists reuse a lookup map. Next priority: representative native/web visual fidelity and whole-product acceptance; Project-filter inheritance and wider relationship integrity remain to audit.

Native typography is consistently Inter, with proportional shared heading line heights verified in a fresh emulator screenshot. Lint, APK and the offline connected suite pass. Full representative-screen fidelity and the remaining product acceptance work remain outstanding.

Today now has source-based native cards and tested local task completion/deadline/Inbox interactions. Seventeen offline instrumentation tests pass. Next visual work: source four-destination navigation and header/date spacing, populated Today comparison, then the remaining representative screens. Full V1 remains unfinished.

Source-based native four-tab navigation and header/date pill are implemented and inspected. Library preserves every former More destination plus Resources, with actual UI verification; daily-note and weekly-review regressions follow Library. Remaining visual work includes populated Today, Inbox/clarification, Project/Note and Calendar, plus desktop comparisons and full product acceptance.

Native Inbox now supports direct unclassified thought capture and source-based typed preview rows, with live counts and relative ages. Eighteen offline instrumentation tests pass, including new inline-save/reopen coverage. A populated Inbox screenshot was inspected. Continue with clarification and capture-surface fidelity, remaining representative screens and full product acceptance.

Native clarification has source-based heading, capture card and destination pills; actual capture/reopen/process flows and fresh build/lint pass. Its populated screenshot was inspected. Continue responsive/large-text and secondary-surface refinement alongside the remaining representative screens and full V1 gates.

Clarification actions now wrap at narrow widths, with verified 200 dp/200% text touch geometry and callbacks. Active capture position is shown in the header. Targeted layout/capture tests and build/lint pass. Continue the broader screen and whole-product acceptance work.

Archived native captures now restore from clarification and display an appropriate header. Actual offline archive/restore/reopen coverage passes, along with the complete 19-executed native instrumentation suite and fresh lint/APK. Next: complete the full offline edit/move/search/delete/reopen gate and remaining representative-screen/product acceptance.

The native offline Task lifecycle gate now covers UI capture/edit/complete/Project-context move/filtered search/delete/reopen plus permanent tombstone checks. Live native Search responds to local record/context changes and deletions, with dedicated Room-backed UI coverage. Full native instrumentation passes with 21 executed tests. Continue remaining representative-screen fidelity, integration restart tests, transfer/performance and final requirement-by-requirement acceptance.

Native related tasks/backlinks and web related tasks/contextual note creation now persist drafts before navigation. Actual Room/Compose and offline production-browser coverage proves the preserved edits. Full native instrumentation passes with 22 executed tests; web checks/build and ten default browser flows pass. Continue remaining visual, integration and performance acceptance; the full goal is unfinished.

Legacy parent-only subtasks now participate in Project-filtered search on both platforms, matching the existing Area inheritance policy without data rewrites. Expanded domain coverage and relevant native/browser flows pass. Continue representative-screen fidelity, broader relationship checks, integration restart and measured performance acceptance.

A measured native FTS query bottleneck is fixed: warm 2,002-record contextual-search median decreased from 2.14 s to 227 ms in the debug emulator fixture. Account/deletion/context behavior passes JVM and relevant real-device instrumentation checks. See PERFORMANCE-SEARCH.md for samples and limits. Continue the broader performance, visual and integration acceptance work.

Calendar invalid-date handling now prevents undated tasks appearing when the field is empty and aligns daily-note availability with repository date format. Actual Room/Compose and dated-note flows pass. Next Calendar work is the source-based interactive month grid/agenda layout; frame 2:596 sample dates need correct dynamic generation because the source month/grid/agenda examples disagree.

Native Calendar month grid/day selection/navigation now work with correct dynamic dates and original source markers. Leap-year/boundary/agenda/daily-note flows pass and a fresh actual-app screenshot was inspected. Continue source-based agenda rows and Calendar disclosure/reminder/accessibility refinements, alongside all remaining full-product acceptance requirements.


Native Calendar now has source-based compact agenda rows and reminder-day grid dots. The expanded actual Room/Compose reminder/date flow and daily-note regression pass, with fresh lint/APK and inspected component screenshot; explicit month-heading contrast was corrected from that inspection. Continue Calendar ordering/orphan/disclosure/accessibility refinements, representative whole-screen comparisons and the remaining full V1 requirements.


Native date validation now matches the shared wire format across all five fields, including previously unchecked Project target dates. Matching cross-platform boundary/leap-day tests and native debug/release test, lint and APK checks pass. Continue broader relationship/non-date validation, representative-screen fidelity, integration/performance checks and full requirement-by-requirement acceptance.


Native relationship-ID/list/text/settings and exact-integer timestamp bounds now guard the domain boundary, with real Room rejection/rollback coverage. Full native offline instrumentation passes (25 executed, two backend skips), alongside 71 JVM tests per build variant, lint and APK. Continue graph-reference/cycle/transport alignment, representative-screen comparisons, restart/transfer/performance checks and full V1 acceptance.


Calendar reminder rows/dots now exclude orphaned/deleted targets and use chronological ordering with consistent row spacing. Actual remote-tombstone and order coverage, daily-note regression, fresh lint/APK and inspected component screenshot pass. Continue Calendar disclosure/accessibility and representative whole-screen fidelity, together with remaining integration/domain/performance acceptance.


Calendar day touch geometry now adapts to normal and very narrow widths, preserving 48 dp cells through reduced padding or horizontal grid scrolling. All dates and month controls pass actual 200% text instrumentation at 360/280 dp, with inspected screenshots and calendar/daily-note regressions, lint and APK passing. Continue date-input disclosure, whole-screen visual/accessibility review and the remaining full V1 acceptance requirements.


Calendar manual date input now uses tested progressive disclosure, retaining selected date/agenda and clearing focus when hidden. Layout, agenda and daily-note regressions plus fresh lint/APK pass; collapsed component screenshot was inspected. Continue representative whole-screen comparisons and remaining accessibility/integration/domain/performance acceptance for full V1.


Native Today now surfaces active Project next actions without forcing dates, with tested opening/completion and archived-Project exclusion. Fresh lint/APK and Room/Compose Today regression pass. Continue populated representative-screen fidelity, wider integration/domain/performance work and full V1 acceptance.


Web saved typed title links now bind to stable IDs without reserializing Markdown; actual offline browser rename/reopen/link/backlink and IndexedDB/outbox coverage pass alongside all default web gates. Continue native/legacy typed-link handling, representative-screen fidelity and remaining integration/domain/performance/full V1 acceptance.


Native saved typed title links now bind to stable IDs with Markdown source preservation and real Room/outbox/rename/reopen/account checks. All 73 JVM tests per variant, lint/APK and full native offline instrumentation (27 executed, two backend skips) pass. Continue legacy-link policy, representative-screen fidelity, wider relationship/transport and integration/performance work, then full requirement-by-requirement V1 acceptance.


Project task lists and next-action selection now include parent-only inherited subtasks on web and Android. Actual native picker/persistence coverage and context regressions plus lint/APK pass; web default gates and browser regressions pass. Continue browser inherited-picker coverage, representative-screen fidelity and remaining relationship/integration/performance/full V1 acceptance.


Browser inherited-subtask next-action selection now has actual offline persistence/reload/Today/completion coverage, including override/completion/cycle exclusions and preservation of parent-only shape. Typecheck and all 11 default browser flows pass (two backend skips). Continue representative-screen fidelity and remaining full V1 acceptance work.


Web sync now exposes active checking and safe retry status even when file transfers fail after metadata ACK. Account-scoped session and rendered-control recovery tests pass, with final web checks/72 tests/build and 11 default browser flows. Continue native status parity, file transfer counts/progress/performance and all remaining representative-screen/integration/domain/full V1 acceptance work.


Native foreground sync now matches web attempt feedback, including safe file-retry status after metadata ACK, account clearing and overlapping-attempt fencing. Actual Room/Compose and concurrent-call coverage plus all 75 JVM tests per variant, lint/APK and 28 executed offline device tests pass (two backend skips). Continue file counts/progress/background-status/performance and the remaining representative-screen/domain/integration/full V1 acceptance work.


Native automatic sync status now observes real account-specific WorkManager jobs, complementing manual session attempts. Persisted queue/retry/last-check feedback, periodic-wait suppression, cancellation/account isolation and newer-worker recovery ordering have WorkInfo, actual WorkManager and Compose coverage. All 78 JVM tests per variant, lint/APK and two targeted device tests pass. Continue configured-worker regression, periodic success history, transfer counts/progress/performance and remaining full V1 representative-screen/integration/domain acceptance.


Native last successful worker checks now persist outside prunable WorkManager output, including the shared periodic worker path. Actual waiting-periodic/account-isolation/fresh-observer coverage and configured SDK-worker durable-history checks pass, alongside JVM/lint/APK checks. Continue representative-screen comparisons, attachment transfer feedback/performance, relationship/integration/accessibility and the complete V1 acceptance audit.


Web durable original-transfer caching now avoids repeat byte reads between bounded integrity rechecks, with file-revision/metadata/backend/account fencing, migration and race/recovery tests. All default web gates and both configured browser Firebase flows pass. Implement native equivalent revision/ledger tracking, measure original-transfer resource costs, complete transfer feedback and remaining representative-screen/domain/integration/accessibility/full V1 acceptance.


Native durable original-transfer caching now matches the web policy, with Room v4 revision/ledger migration, zero-byte-read reopen evidence and race/recovery tests. All 82 JVM tests per variant, lint/APK, 29 offline device tests and expanded SDK ledger/transfer/deletion gate pass. Continue measured transfer costs and user-visible progress, configured-account regression, representative-screen fidelity and all remaining full V1 acceptance work.

Native Project detail now keeps creation and editing forms behind explicit disclosures, preserving the source outcome/next-action/task-first hierarchy. Hidden forms retain unsaved drafts; task creation and related-work navigation preserve context and edits. Project/subtask rows use stable creation order rather than update order so completion does not reorder work, with explicit account/deletion fencing. A populated production MainActivity screenshot was inspected against fresh Figma 2:323 context; Room/Compose and full offline instrumentation pass. Continue full Project large-text/accessibility and secondary-surface acceptance, remaining Note/Calendar/desktop representative comparisons, authenticated launcher/reminder restart, transfer feedback/performance and the full requirement-by-requirement V1 audit. The full goal remains active and unfinished.

Native Note detail now follows inspected source 2:517: optional editable title/context, rendered content first for saved notes, a reference pill and integrated backlinks, compact primary formatting/Link note/Attach actions, and a working save/archive/restore/delete menu. Empty notes still open directly for editing. Selection survives Link note and More formatting popup focus changes, without changing stored Markdown; native preview obeys Compose font scale. Unchanged related-work navigation is immediate and leaves record versions untouched, while dirty drafts save quietly before navigation. The toolbar and attachment list share one picker/busy/error state. Production activity/offline/picker cancellation and 200% text checks pass, as do 83 JVM tests per variant, lint/APK and the complete 39-executed offline device suite (two backend skips). Next: finish block-level native Note typography, global contrast/secondary-surface and full screen-reader/keyboard review; compare the desktop Note editor and remaining representative screens; complete authenticated launcher/reminder restart and transfer-progress/performance gates; then perform the full requirement-by-requirement V1 audit. None of those original requirements is deferred by this progress entry; the full goal remains active and unfinished.


- Native primary-control contrast continuation (2026-10-01): retained Night blue #3270e6 and changed foreground text to white for the shared Material onPrimary token, generic primary Action, capture FAB, Today Process, Project Start, non-empty Inbox Save and blue Glance widget buttons. Warm #f5eee7 on this blue measured 3.9744:1; white measures 4.5693:1, meeting 4.5:1 for ordinary text. Secondary/warm controls retain their existing foreground. Fresh lint, debug APK and test APK builds pass. Actual WidgetRenderTest, TodayLayoutTest, ProjectOverviewTest (two cases) and InboxLayoutTest pass: five device tests total. Current Inbox normal and Today 280 dp large-text screenshots were pulled; the inspected Inbox fixture shows disabled empty-draft Save, so that screenshot does not establish active-Save color. Full suites, configured Firebase gates, TalkBack traversal and the remaining whole-product contrast/visual/integration/performance requirements were not rerun or completed by this focused change. Full V1 goal remains active.


- Desktop Note continuation (2026-10-01): reread the original full specification and inspected fresh Figma 3:402 with screenshot. Measurements, adaptations and source-asset verification are recorded in docs/figma/3-402-measurements.md. NoteRecord.tsx now implements the source note-first760/300 px two-column layout, optional directly editable title and PARA context, safe rendered body, final standalone resolved reference pill,96 px compact primary formatting/Link note/Attach controls, stable backlinks and actual Details. Organize fields use progressive disclosure. Empty notes open in editing; saved nonempty notes read first. Remaining Markdown formats and explicit Save/Archive/Restore/Delete remain reachable. Archive persists drafts before state changes. Unchanged related navigation avoids record updates and confirmation overlays; dirty drafts save quietly before opening related work.

- Extracted shared AttachmentList.tsx retains the production original-file repository path and shares picker/busy/errors with the new toolbar. Its hidden picker is removed from keyboard traversal and assistive exposure; visible Attach remains the entry point. The Link note dialog supports search, keyboard selection, preserving the textarea selection across focus changes and Escape focus restoration. A parsed-boundary unit test prevents code/list/quote/escaped/emphasized/inline/unresolved wiki text from becoming reference pills, without changing stored Markdown.

- New actual production-PWA/browser Note test restores the source-like fixture offline, checks exact desktop column/card widths, nine exact local marker URLs, natural loading and28/18/32/24 px geometry, verifies action/input/disclosure containment at1024/390/320, navigates reference/backlinks, reads the exact IndexedDB updatedAt to prove pristine navigation does not write, replaces selected text through keyboard-activated Link note, cancels via Escape and checks focus, opens the real file chooser from Attach, downloads original bytes unchanged, persists hidden context/body drafts through archive/restoration, then reloads and checks local content/original/backlink persistence. Screenshots web-note-populated.png and web-note-1024.png/web-note-390.png/web-note-320.png are under artifacts/visual; normal,1024 and320 captures were inspected against the source. Flowing content/actions remain reachable rather than using source clipping. Complete browser zoom/screen-reader traversal and secondary surfaces remain separate acceptance work.

- Web checks pass:78 tests in20 files, typecheck, lint and production build. The expanded default browser suite passes12 flows, with two configured-Firebase skips; artifacts/verification/web-note-offline.txt and web-note-unit.txt preserve results. Existing context, weekly-note and note backlink expectations were adapted to the actual disclosure/read-first/card semantics. The final hidden-picker keyboard adjustment was additionally checked with fresh typecheck/lint/build and the source Note plus original Markdown/attachment browser flows; artifacts/verification/web-note-final-focused.txt preserves those results. No native/cloud/schema code changed and those gates were not rerun. Full V1 goal remains active: remaining representative Calendar/desktop Project and other fidelity/accessibility, authenticated launcher/reminder cold restart, transfer feedback/performance, relationship/domain and complete requirement-by-requirement acceptance must still be completed. No original requirement is deferred by this entry.


- File-sync progress continuation (2026-10-01): both engines now publish account-fenced file totals/checked/retry counts, current filename and checking/uploading/downloading/removing phase after metadata pull. Counts explicitly include cached integrity checks and deletion cleanup; stale metadata/owners are excluded until a subsequent eligible run. Actual Firebase upload byte callbacks drive percentages, bounded to twenty updates per file, with listener cleanup and late-callback fencing. Downloads retain the bounded verified getBytes path and report an indeterminate phase rather than invented byte percentages. No outbox, cloud schema or backup format changed. Manual/web snapshots clear on namespace/sign-out, preserve safe failed-file counts and recover on retry. Native SyncWorker uses a conflated channel and awaited WorkManager progress writes, so callbacks never wait for its database. Account-scoped WorkInfo observation selects current running-job progress, ignores finished/cancelled details and preserves the existing durable last-check history. Native account primary buttons now use the theme's white onPrimary foreground. Whole-product contrast acceptance remains separate.

- Meaningful tests cover hundred-step upload callbacks bounded to twenty updates, upload/download/deletion phases, unrelated success despite a failed original, durable cached checks, exact original bytes, retry recovery and stale callbacks after completion/stop. Account-session and rendered web/native controls cover failed-file counters, active percentages and account/sign-out clearing. Native WorkInfo tests cover current immediate/periodic progress selection and codec/finished/cancelled behavior. Actual Room/Compose status at280 dp and200% font scale passes long-filename containment and retry/recovery/fencing; final native-file-sync-large.png was recaptured and inspected. The test's initial asynchronous assertion and exact-vs-substring assertion were corrected; a root host Box now constrains the intended component width and cleanup no longer masks primary assertion failures.

- Final web checks pass:80 tests in20 files, typecheck, lint and production build. Twelve default browser flows pass with two backend skips (web-file-sync-offline.txt). Nine actual Auth/Firestore/Storage emulator/rule tests and both configured browser account/original flows pass (web-file-sync-configured.txt). The real browser upload request is held only by the test so the production UI's active filename/0% status and disabled Sync now can be checked and captured; releasing it produces completed file counts, and a separate browser retrieves original bytes offline. The inspected active screenshot is web-file-sync-active.png. The ordinary web build is restored after the configured test; web-file-sync-unit.txt preserves the unit result.

- Native test/lint/APK and complete offline instrumentation pass:85 JVM tests per debug/release variant,42 XML entries/40 executed/two configured-backend skips,zero failures/errors; native-file-sync-offline.xml preserves the full report. Console inflated counts are not used. Native Firebase SDK gate passes real upload byte callbacks and verified128 KiB download/ledger/isolation/deletion (native-file-sync-configured.txt). The configured production application/auth/WorkManager gate additionally observes actual running-job file progress and automatically uploads a4 MiB original without manual sync, verifies Storage bytes and metadata ACK, and retains the existing guest merge/offline-reconnect/sign-out/reopen flow (native-file-sync-worker-configured.txt and corresponding XML). Measured on this emulator:4,194,304 original bytes,99 ms local original capture commit,5,324 ms subsequent automatic upload including scheduling,one observed running file; native-file-transfer-metrics.json preserves this single sample. It is not a peak-memory, maximum-file, slow-network or full-account performance acceptance result.

- Fresh lint/default APK/test APK builds pass after the final paint-only primary-label correction and configured-test helper addition. Lint has zero errors,54 warnings and one hint. Two final manual AccountSyncStatusTest cases pass to collect the current screenshot (native-file-sync-final-render.txt). The default APK is apps/android/app/build/outputs/apk/debug/app-debug.apk and was checked to contain no active nook-firebase.json; the script restores the original configuration. Wi-Fi/mobile data are restored disabled and dumpsys reports no active default network. Emulator gates used demo-nook with JDK21 and process-scoped PowerShell script execution; no unrelated Firebase project was used. Broader largest-file/account/transfer performance, authenticated launcher/reminder cold restart, remaining Calendar/desktop Project/other representative fidelity and accessibility, relationship checks and the complete original requirement-by-requirement audit remain open. The full goal remains active; no original requirement is deferred by this progress entry.

### Calendar agenda and source comparison — 2026-10-01

- Re-read Figma 2:596 with its screenshot through figma-design-to-code. Existing native CalendarMonth and CalendarAgendaRow already use the source Night surfaces, 20/18 dp corners, 34 dp selected marker and 5 dp scheduled marker. Both marker SVGs are nonempty local originals with matching root dimensions and bitmap/Image callsites. Calendar navigation and daily-note controls remain functional.
- Calendar agenda now shows a separate deadline on the do-date card (Do date · deadline Oct 2), retaining the deadline on its own calendar day. Cross-year deadlines include the year. Invalid optional dates are ignored. Agenda and reminder target/marker filtering now explicitly fences the current account, archived records and tombstones; stable creation/id ordering prevents list jumps.
- 87 JVM tests pass in each debug/release variant; debug APK/test APK builds and lint pass (0 errors, 54 warnings, 1 hint). CalendarAgendaTest and CalendarLayoutTest pass offline (native-calendar-offline.xml). A subsequent manual production CalendarVisualTest plus CalendarLayoutTest run passes 2/2 and captures native-calendar-populated.png and native-calendar-large-text-280/360.png; images inspected. The 280 dp, 200% grid retains disjoint 48 dp date targets with horizontal scrolling.
- Source comparison is evidence, not a declaration of complete fidelity: the production screenshot retains month navigation, explicit date/daily-note controls, capture FAB and Library selection. Its calendar uses real Gregorian dates; the source screenshot inconsistently labels October, Agenda Sep 30, and two selected 30s, so those fixture anomalies are not implemented as calendar behavior. Source-like populated cards now preserve the deadline detail. Further source spacing/control placement, larger-text full-agenda checks and the broader original acceptance audit remain open. The full goal remains active.

### Desktop Project workspace — 2026-10-01

- Inspected current Figma 3:322 with its returned high-fidelity context and screenshot using figma-design-to-code. ProjectRecord.tsx now presents outcome/progress and next action above desktop Tasks, Notes & resources and Activity cards, with editable project fields/attachments/AI behind Project details. Empty projects disclose their fields and task entry initially. Add note remains an accessible header action; Activity is explicitly the latest saved changes, derived from records rather than invented sample history.
- Header shows the actual Area, target date and completion percentage. The project header and sidebar use all five original 3-322 SVG files, including nine visible slots; root metadata is 28/18/18/32/24 px and browser assertions verify local loading and rendered geometry. Related tasks have explicit account/deletion/archive fences, stable creation/id ordering, inherited project resolution and separate completion/open controls. Unscheduled non-next tasks say Later. Completing the next action removes Start. Dirty project drafts persist before related navigation, adding tasks/notes or archiving; pristine navigation avoids writes. New notes inherit project context.
- project.spec.ts restores a source-like ZIP fixture into the production PWA and runs offline. It asserts all five desktop card boxes: outcome (296,138,760,132), next action (1080,138,300,132), tasks (296,298,620,510), notes (940,298,440,246), activity (940,568,440,240). It verifies original asset slots, no horizontal page overflow at 1024/390/320 px, completion/reload, hidden draft persistence, archive/restore and note creation. Context/archive browser tests were adapted to the explicit details disclosure. Narrow checkboxes have 44 px targets.
- Typecheck, lint, production build and all 80 unit tests pass. Full default browser suite passes 13 flows, with two configured-backend skips (web-project-offline.txt). Final Project-only run passes after source-box height assertions and checkbox margin correction (web-project-final-render.txt). Screenshots web-project-populated.png and web-project-1024/390/320.png are retained; desktop and 320 px images inspected. No Android code changed this phase, so native results remain those from the Calendar phase. Cloud backend flows were not rerun for this UI-only phase.
- Full original goal remains active. Next acceptance work includes authenticated Android launcher/reminder cold restart, successful system original/photo input, broader accessibility/performance evidence, remaining Calendar spacing and a concrete complete requirement-by-requirement audit plus handoff. These are not deferred V2 claims.

### Authenticated Android cold surfaces and durable sign-out — 2026-10-01

- Added opt-in ColdAccountSurfaceTest and scripts/test-native-cold-surfaces.ps1. The script configures only demo-nook emulators, installs the configured APK, registers a test account, disables Wi-Fi/data, force-stops app.nook between test phases and checks distinct OS PIDs. Initial/final cleanup plus sign-out verification make eight successful one-test phase invocations (six distinct methods). The final version deliberately lets the production widget, ReminderWorker or registered shortcut activity initiate Auth restoration before any test helper calls activeRepository.
- Cold Inbox/Today/Project widgets render the authenticated local namespace through real Glance RemoteViews and a bound AppWidgetHost ID; a unique post-sign-in guest capture stays absent. Wrong-account completion callbacks are ignored, while authenticated widget completion commits locally and leaves a durable outbox. Cold ReminderWorker posts the authenticated deadline notification, archives its reminder, and exposes Capture/Open/Later actions. Capture uses the actual manifest task ShortcutInfo intent and production CaptureActivity, saving a task-like Inbox capture locally with an outbox while offline. This proves process startup and system-surface behavior; it does not claim physical-launcher pin placement or a successful camera/document-picker workflow.
- The immediate post-sign-out restart check initially reproduced a real defect: a cached Firebase identity returned after sign-out had completed. Fixed with SignedOutFence (synchronous device-local Boolean commit), production AppGraph wiring and FirebaseAuthPort identity masking/startup clearing. Only successful explicit credential establishment clears the fence. No keys or user IDs are stored in it. SignedOutFenceTest verifies reconstruction, configuration scoping and Boolean-only contents. The final cold gate passes both initial and final immediate sign-out restarts; native-cold-surfaces.txt records all eight successful phases. Test fixture label collisions from legitimate guest merges were corrected to unique post-sign-in data; empty capture Save remains correctly disabled until text exists.
- Fresh Android test/lint/debug APK/test APK checks pass: 88 JVM tests in each debug/release variant, zero failures/errors. The production configured account UI + guest merge + automatic 4 MiB original WorkManager transfer + disconnect/sign-in/recreate gate passes again after the fix (native-cold-auth-regression.txt). Full default offline connected XML has 49 entries, 41 executed, eight opt-in skips and no failures/errors (native-cold-offline.xml); inflated console totals are not used. Native lint has zero errors, 56 warnings and one hint in that full run. The final stricter cold tests compile and pass after this full run; production code is unchanged between them.
- The wrapper restores the original configuration and network state and rebuilds/reinstalls the default APK. Active nook-firebase.json is absent and Wi-Fi/mobile data are both 0 after the final gate. No unrelated Firebase project was used. The full goal remains active. Next work should be the explicit original requirement-by-requirement audit, with concrete fixes/evidence for successful system original/photo input, widget large-font behavior, any remaining obvious Calendar/control fidelity and accessibility issues, performance and clean-install/final handoff gates; broad historical pending labels are not completion evidence.

### System originals and clean web installation — 2026-10-01

- SystemOriginalInputTest uses the real Android DocumentsUI and camera ActivityResult flows offline. Exact UTF-8 document bytes and ownership/outbox persist after activity recreation. The real AOSP camera saves a caption-free JPEG with exact byte preservation: 74,654 bytes, 1392 × 1856. This verifies the API 36 emulator providers, not every vendor camera or document provider. Final system-input plus shared-image Gradle gate passes four tests with no failures/errors/skips; native-original-input-final.xml/txt and native-system-camera-result.json preserve evidence.
- Clean npm ci, typecheck, lint, 80 unit tests, production build and 13 offline browser flows pass. Nine configured Firebase rules/SDK tests and two configured browser account/original flows pass on demo-nook. web-clean-* artifacts preserve results. scripts/test-web-backend.ps1 contains the same tested rules-plus-auth wrapper previously used from the artifact directory.
- Lockfile overrides pin @grpc/grpc-js 1.14.5 and uuid 11.1.1 for gaxios 6.7.1, retaining its CommonJS v4 API. Production npm audit reports zero vulnerabilities; the entire development tree retains three moderate advisory entries along Firebase CLI → Pub/Sub → OpenTelemetry core 1.x. No incompatible OpenTelemetry major override or Firebase downgrade was forced. npm-audit-production.json and npm-audit.json distinguish these results. App/cloud test gates pass with the refreshed lockfile.
- Full original goal remains active; these concrete completed gates replace the historical pending camera/file-input and clean-install labels.

### Device feedback: capture keyboard, scheduling and project assignment — 2026-10-01

- The in-app quick-capture sheet skips the partially expanded state, uses a bounded four-line thought input and puts Photo beside Task/Link. Its content responds to IME insets and scrolls when a smaller viewport needs it. The share/shortcut capture surface also bounds its input and provides scrolling.
- Added DateChoice and TimeChoice: naturally displayed local dates, Today/Tomorrow/Next week shortcuts, a native calendar picker and Clear; a native clock picker respects the device's 12/24-hour preference. Task do dates, deadlines and project targets stay separate and retain their ISO storage format. Reminders select date and time separately; a newly chosen reminder date defaults to 09:00. Inbox review uses the clock picker. This implements tap-based scheduling, not free-form natural-language parsing.
- Clarify exposes Assign to project before conversion. Tasks, notes and resources can inherit the chosen project. NookRepository.process validates a live same-account project and commits assignment, capture tombstone and attachment reassignment in the same Room transaction. The Task capture chip is an Inbox hint; conversion remains an explicit Clarify action.
- Added an actual-IME production activity flow checking that Photo and Save are displayed and Save's bounds remain above the keyboard; it captures a task-like thought, chooses a project in Clarify, chooses Tomorrow, saves and recreates the activity with project/date intact. Added real calendar/clock dialog confirmation/cancellation/clear checks and Room rejection checks that preserve the original capture. Existing context/share/recurrence flows are retained. The older recurrence test now scopes Today to the date control and waits for the conversion snackbar to clear before touching a bottom-of-editor Save control.

Final feedback gate: gradlew test/lintDebug/assembleDebug and the ten-test CaptureFlow/CaptureProject/DateChoice/RecordContext/ShareCapture device suite pass. JVM XML records 88 tests per debug/release variant with zero failures/errors. Connected XML records ten tests, zero failures/errors/skips. Lint has zero errors, 56 warnings and one hint. native-mobile-feedback.txt/xml preserve the final successful run. Default APK was reinstalled on the offline emulator (Wi-Fi/data 0/0); no active nook-firebase.json exists. The separately named tested build is artifacts/apk/nook-feedback-debug.apk (SHA-256 B04D202AE978204AB9996DF0655437B8089B5973D7021A3F048BD121BA509B19). This focused native gate does not claim the complete original V1 acceptance audit or every physical-device keyboard layout is finished; the full goal remains active.
