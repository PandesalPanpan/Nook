# Clarification prototype

Open `/prototype/index.html` on the web development server. For a phone on the same Wi-Fi, use the server's network address with that path. The prototype uses sample data in its own browser storage key, `nook-clarification-prototype-v1`. Reset prototype restores the examples. It does not access Nook's database or sync.

## Try the flow

The revised prototype opens on Today and follows the saved MarticioUI Night references: Inter typography, warm tokens, rounded cards, pill controls, Today actions, Coming up, and the Inbox summary. Primary navigation is Today, Inbox, Projects, Library.

1. Open the bedroom lighting thought in Inbox and edit its text.
2. Choose Task or Note, or choose Split for a linked Note + Task. The capture text supplies the ordinary Task title or Note content; Split adds an action field. More options exposes Note title/content changes.
3. Choose a Project, Area, or Resource as the primary home. New homes can be created from the picker.
4. Link Lighting ideas or another existing item.
5. Schedule the Task for Tomorrow, or open Schedule for the calendar and other shortcuts. A Task defaults to no date. More options exposes a separate Deadline.
6. Save & next. An optional preview is available under More options.
7. Open History to see the original capture and its resulting items.
8. Open either resulting item to inspect the connections. Open its home in Library to find the filed items.
9. Complete the Task from Tasks, change its schedule, or move it to another home.
10. Try Capture, the thought's More menu for Archive/Delete, Restore in History, and the capture widget concept under Library. Add task on Today needs only the action text, with optional scheduling and assignment.

The home picker groups Projects, Areas, Resources in that order with capitalized type descriptions. A matching Project name can produce a one-tap suggestion; the home is not assigned until selected. Note titles derive from the first line. Split suggests an action only when an explicit action phrase is present. These are local text heuristics, not AI interpretation.

## Scope

The prototype demonstrates one-at-a-time processing, editing, Note/Task splitting, one primary home, links visible from both connected records, processed history, schedule shortcuts, a custom calendar, separate deadlines, readable capture times, distinct icons, and centered widget content. Relative times are used for captures less than one hour old; older captures show an absolute local date and time.

The Android widget shown here is a browser visual concept; the production Android widget is native. Photos, attachments, notifications, recurrence, cloud sync, and production record migrations are outside this standalone prototype. Task links are explicit related-item relationships; this prototype does not parse Markdown wiki links. Production Android and web integrations retain their existing platform architecture.

## Integration outcome

Production Tasks and Notes now support Resource membership, while legacy records with multiple explicit homes preserve those associations until edited. Processing retains source captures as history and writes destinations idempotently with deterministic IDs. The V1/V2 reader and deployment policy is documented in [`SCHEMA-COMPATIBILITY.md`](SCHEMA-COMPATIBILITY.md).

## Validation

A mobile browser walkthrough exercised Note + Task creation in a Resource, links to a Note and Project, Tomorrow scheduling, the custom calendar, preview confirmation, next-capture navigation, processed history, and opening the resulting Note. Both resulting records retained the Resource home, related links, and the split connection. No browser runtime errors were observed in that walkthrough.
