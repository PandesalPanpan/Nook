# Record schema compatibility

Record schema versions describe the JSON payload for an individual record. Existing V1 records remain valid and are not rewritten during upgrade. Both current clients read V1 and V2 records; an edit preserves the stored version unless that edit adds a V2 field.

V2 is used for processed-capture history and resumable clarification drafts, Task Resource membership, explicit Task–Note and collection links, and the capture source ID on clarified Tasks and Notes. The updated Task, Note, Capture, Android, web, backup, and Firestore validators share these fields. Resource membership is a primary home for a Task or Note. Legacy records with more than one explicit Project/Area/Resource reference retain every stored association until the user chooses a new primary home in the editor.

Firestore rules accept V1 and V2 records. Deploy the rules before web or Android code that can write V2. Web writes continue to accept V1 records from installed Android builds. Android builds accept V1 records written by older web deployments. Deploying a new reader does not change existing user data, and exported backups retain the original per-record schema version and fields.

The permanent-tombstone conflict rule still applies at every schema version. A V2 update never clears a deletion. Processing uses deterministic result IDs derived from the capture ID and result role, then atomically commits the result records, attachment ownership, and processed-history record in the local database transaction. Repeated local requests reuse those results. Offline clients processing the same capture with the same result roles converge on the same IDs. If two clients choose different result types while offline, each result remains a separate item connected by the capture ID/link data; a deleted result is never recreated by a retry.

Older strict V1 readers reject records containing V2 fields. Users should update both apps before syncing records that use Resource membership on Tasks, explicit related links, or processed history. During a mixed-version period, those older clients may report an unsupported record and pause sync for that account until upgraded. No server-side projection strips fields, because doing so would make a successful-looking sync lose the new relationships. Newer clients continue to read old V1 data and preserve legacy associations.

## Production deployment — 2026-10-05

The V1/V2-compatible Firestore rules in `firebase/firestore.rules` were deployed to production project `nook-app-e3f48` before the updated Android and web clients. Firebase confirmed that the rules compiled and were released to Cloud Firestore. Emulator rule tests passed all 10 cases.
