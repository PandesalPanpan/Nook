# Goal: integrate the approved clarification UX and release Nook

Implement the approved revised prototype in production Android and web, verify it, publish a new signed Android APK on GitHub Releases, and update the existing web deployment. Use the current checkout at `Y:\Codes\Nook`. The user intends to run this goal with Luna 6 at max reasoning.

The user authorizes implementation, necessary schema/data migrations, meaningful tests, commits, pushes, release tags, GitHub Release publication, and deployment to the existing Nook web site. Do not stop after a plan or a local build. Do not ask for the same authorization again. Do not purchase services or change hosting providers. If an external prerequisite prevents publication, finish the work that remains possible and report the specific blocker and what actually shipped.

## Read first

- Applicable `AGENTS.md` and relevant skills.
- `docs/CONTEXT.md`, `docs/ARCHITECTURE.md`, `docs/ACCEPTANCE.md`.
- `docs/CLARIFICATION-PROTOTYPE.md`.
- `apps/web/public/prototype/index.html`, `app.js`, and `style.css`.
- Revised screenshots: `artifacts/visual/prototype-v2-today.png`, `prototype-v2-clarify.png`, `prototype-v2-homes.png`, `prototype-v2-calendar.png`.
- Existing production Android/web screens and shared design tokens; saved MarticioUI references in `docs/figma/`.
- `docs/ANDROID_RELEASES.md`, `docs/android-release-verification-v0.2.3.md`, `scripts/release-android.mjs`, and `.github/workflows/android-release.yml`.

Inspect current git status before editing. There are existing unrelated `.firebaserc` and visual artifact changes, plus the uncommitted prototype/glossary work. Preserve unrelated work and commit only changes belonging to this goal. Do not reset, delete, or blindly include unrelated files to satisfy the release helper's clean-main requirement. Use an isolated checkout/worktree if needed, carrying the approved prototype and glossary changes explicitly.

## Visual and interaction constraints

This is a UX integration into the existing MarticioUI Night application, not a UI overhaul. Preserve its Inter typography, colors, card shapes, pill controls, navigation, mascot, and responsive desktop/mobile structure. Preserve Today actions, Coming up, and the Inbox summary. The original prototype was rejected for changing the design and exposing an overwhelming form; follow the revised version.

Use **“Add to…”** for the assignment control and **“Add to a Project, Area, or Resource”** for its picker heading. Do not use “Assign to a home” in product copy. Group home choices as **Projects, Areas, Resources**, in that order. Capitalize their type descriptions consistently. “Primary home” can remain an internal glossary term.

Use recognizable, consistent icons for actions and item types where useful instead of indistinguishable circles, while retaining the existing design system. Keep accessible touch targets, labels, focus behavior, contrast, large-text support, and reduced-motion behavior.

## Approved experience

1. Capture remains fast and saves to Inbox. Existing text, task-hint, link, photo, share, widget, and shortcut entry points must continue working.
2. Inbox processing focuses on one thought at a time, with Save & next. A capture is editable after capture and during clarification; save/recreation/navigation must not lose edits.
3. Ordinary clarification shows one editable thought, Task/Note choices, optional Add to…, and one clear save action. Do not require retyping the capture into a separate title and content field.
4. Use sensible defaults: a Note uses the capture text and a title derived from its first line; a Task uses the action text and starts unscheduled; respect an explicit captured Task hint. Do not silently guess a date, deadline, or organizational assignment. Matching Project suggestions must be visible, optional, and accepted explicitly. Do not add a paid AI dependency.
5. Optional Split creates a Note and a separate linked Task from one capture. Both are editable before saving. Expose the action field when Split is selected. Optional title/content adjustments and preview are available through More options.
6. Tasks and Notes can each belong to a Project, Area, or Resource. There is one primary home. A Project's Area provides inherited context. Other associations are related links rather than additional equal homes. Choosing or creating a home must work end to end, and the saved item must appear in that collection.
7. Related items remain separate. Their connection is visible and navigable from either side. Support Task–Note and Note–Note links and related connections to organizational collections shown by the prototype. Preserve existing Markdown wiki links/backlinks. Do not introduce another competing link system without reconciling its behavior and presentation.
8. Saving removes the capture from Inbox and retains processed history with original wording, capture time, processed time, and links to resulting items. Preserve the original text and edits made during clarification so the source can be revisited. Processed history is distinct from archived thoughts.
9. Archive and Delete belong in the thought's labelled More menu, with recognizable icons and clear descriptions. Archive is reversible; Delete requires confirmation. Avoid prominent competing destructive buttons in the clarification form. Provide restoration from Archive.
10. Create Tasks with the action text alone. Schedule and assignment are optional; advanced controls stay behind disclosures. Preserve task completion, subtasks, reminders, recurrence, and Project next-action behavior.
11. Schedule sets the planned/do date. Provide Today, Tomorrow, Next week, and a custom in-app calendar. Deadline is separate, optional, and behind More options. Replace the frustrating native date-picker interaction on relevant Task/clarification surfaces with the approved consistent date UX. Preserve ISO date storage, local calendar semantics, clear-date behavior, and existing reminder/time behavior.
12. Capture times use “just now” or minutes for the first hour, then an absolute local date/time such as “Sep 30, 2026 · 9:00 PM”. Respect locale/time preferences without unbounded minute counts.
13. Fix the Android orange + Capture widget so the plus and label are centered together across supported widget sizes and large-text settings. Its tap action must still open capture immediately. Other widgets must retain their behavior.

## Data, compatibility, and reliability

The prototype uses sample browser storage and is not production architecture. Integrate through the existing repositories, shared schemas, Room/Dexie, search indexes, durable outbox, Firebase transport/rules, and backup formats.

Production Tasks currently lack Resource membership; current processing creates one destination and permanently tombstones the capture; legacy records may have several explicit context references. Resolve these differences deliberately across both clients and Firebase rules. Choose compatible schema/migration/versioning and deployment sequencing; strict older readers can reject newly added fields. Document the compatibility policy. Preserve existing user data and explicit legacy associations rather than silently deleting or rewriting them.

Processing and splitting must be atomic and idempotent: repeated taps, retries, concurrent clients, and failed writes must not duplicate destinations, strand a capture, lose attachments, or resurrect deleted data. Preserve original attachments and ownership through conversion/splitting; for a split, a Note can retain originals with the Task accessing it through the link. Record any necessary tradeoff. Account, deletion, archived-target, missing-reference, and cycle checks must remain enforced. Missing/deleted linked targets should be handled gracefully in history and related-item views.

Maintain offline capture, processing, editing, search, and navigation. Sync, backup/export/restore, and in-place Android upgrades must preserve the new relationships and processed history. Existing unrelated records must remain usable. Save dirty drafts before related navigation and preserve them across recreation where the existing app promises recovery.

Update the glossary only for domain terms. Put implementation/compatibility decisions in appropriate technical docs; use ADRs only for consequential decisions with genuine tradeoffs.

## Validation and completion gates

The user explicitly requests implementation verification for this goal. Add meaningful regression coverage and run appropriate existing checks, including web typecheck/lint/tests/build, relevant browser flows, Android unit tests/lint/build, and relevant emulator instrumentation. Exercise Firebase rule/sync checks for schema changes where the configured environment allows it. Do not claim skipped backend checks passed.

Verify at least:

- Capture edit → Task/Note → existing or newly created Project/Area/Resource → collection navigation.
- Split → two linked items → reciprocal navigation → history → reload/recreation.
- Empty and unscheduled defaults; scheduling shortcuts/custom dates; optional deadline; picker cancellation retaining drafts.
- Archive/restore and confirmed Delete; unavailable links; repeated processing and transaction failures.
- Offline persistence; cross-account isolation; attachments; sync/backup round-trip.
- Existing completion/subtask/reminder/recurrence flows and Today/Coming up behavior.
- Narrow web/Android layouts, large fonts, touch/focus accessibility, and widget centering/actions.
- Signed APK in-place update preserving pre-existing data on a disposable emulator when available. Never uninstall or clear the user's real device data.

Inspect screenshots against the existing MarticioUI references and approved revision; fix regressions before release. Report exact checks performed and material limitations.

## Release and web publication

Inspect the current remote release/tag/version state and select the next appropriate unused version; do not assume historical v0.2.3 is still latest. Increment versionCode monotonically and keep the permanent signing identity and package `app.nook`.

Use the documented release helper/workflow after preparing a clean intended source commit and non-empty release notes. Publish a stable GitHub Release in `PandesalPanpan/Nook` containing the signed `nook-v<VERSION>.apk` and matching `update.json`. Wait for Actions completion. Verify the public release assets, checksum, package/version/signature, and latest-release updater discovery; a successful tag push is not a completed release. Do not replace existing tags or expose signing credentials.

Build and deploy the updated web app to the existing `nookmarticio` site / `https://nook.marticio.com` using its documented configuration and relevant Netlify skills. Earlier deployment was blocked by account credits; recheck current status instead of assuming the historical blocker still applies. If publication is still unavailable, preserve the finished build and report the exact blocker. Do not silently switch providers or claim the website is live based on a local build. Deploy any required compatible Firebase rule changes through the project's established workflow.

Finish with the release/version, direct GitHub Release and APK links, public web status/URL, implementation summary, verification evidence, and any remaining external blocker. The intended outcome is working production Android and web UX with a published compatible Android update.
