# PARA context

## Glossary

- **Capture (thought):** An item in the Inbox awaiting clarification.
- **Clarification:** Deciding whether a capture becomes a Task or Note and optionally assigning its organizational context.
- **Task:** An action to do.
- **Note:** Information to keep.
- **Organizational context (home):** A Project, Area, or Resource associated with a clarified item; distinct from its Task or Note type.
- **Project:** Work toward an outcome; can contain Tasks and Notes.
- **Area:** An ongoing responsibility; can contain Tasks and Notes.
- **Resource:** A collection around a topic or interest; can contain Tasks and Notes.
- **Primary home:** The single Project, Area, or Resource where a Task or Note is filed. An item's Project can provide its Area context.
- **Related link:** A connection to another item without assigning an additional primary home.
- **Linked items:** Separate records connected by a related link visible from either item. Linking does not embed one item inside the other's content.
- **Split capture:** One capture clarified into a Note and a related Task, with each resulting item independently editable.
- **Processed capture:** A capture that has left the Inbox after clarification, retained in processed history with its original wording and links to the resulting items.
- **Schedule (planned date / do date):** When the user intends to work on a Task.
- **Deadline:** When a Task must be finished, independently of its planned date.
- **Focused processing:** Clarifying one Inbox capture at a time, with Save & next advancing to the next capture.

## Existing context reference

Projects can belong to an Area. Tasks, Notes and Resources can reference a Project and/or Area; Notes can also belong to a Resource collection. Resource collections retain their description, optional URL, linked notes and original attachments.

Resource projectId/areaId fields are optional additions to the V1 JSON payload. Existing records remain valid. Room/Dexie store payload JSON, so this addition needs no table migration or database reset. Backups retain the relationships. Both applications and the included Firestore rules must be updated together before syncing these fields; older strict V1 readers will reject fields they do not recognize.

An explicitly assigned Area wins. When a record has no explicit Area, Area search and related-work lists derive it from its Project. A legacy task without its own Project/Area can inherit through its parent task chain. The first explicit Area or Project along that chain wins; a missing explicit Project does not fall through to a different parent context. Resolution uses only undeleted Projects and Tasks in the same account. Traversal is iterative and stops on repeated IDs, wrong parent kinds or missing parents. Search and editor lists build one account-filtered lookup map and reuse it for their results. It never rewrites a child or revives deleted data. A missing/deleted Project leaves the derived Area unavailable. Existing explicit associations remain explicit when their Project changes.

Context changes save locally and enqueue the ordinary durable record operation. Cloud writes use the owner namespace and normal conflict/tombstone policy. Resources validate optional reference ID syntax in web, Android and Firestore rules.

Parent-only legacy subtasks, deleted parents, cycles and account boundaries have automated coverage. Related-work navigation and draft-preservation flows, source-based Project/Note comparisons and the 2,002-record contextual-search sample have been exercised. ACCEPTANCE.md tracks the final gate; these samples do not claim unbounded-account performance.

Project-filtered local search also derives context through legacy parent-only Tasks. The resolver walks same-account, live Task parents with cycle detection; an explicit non-empty projectId stops inheritance, including an unavailable reference. Search matches derived IDs only against live Projects in the current account. Project records match their own IDs directly. No inherited fields are written back to records. Web queries load Project/Task parents through the account-kind compound index; both platforms build the parent lookup once per search.
