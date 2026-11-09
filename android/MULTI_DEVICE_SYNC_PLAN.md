# Multi-device synchronization plan

## Current state and recommendation

The Android tablet app is an offline-first, single-device application. Room is
the tablet's source of truth. Google Drive scheduled backups preserve roster
and attendance snapshots, but they are **not synchronization**: they do not
merge another tablet's changes and do not contain face templates. The
password-protected `.sewabackup` transfer merges roster, attendance, and
portable face templates when an admin manually restores it.

For two or more tablets that should show one shared roster and attendance as
soon as the app opens, use a **central synchronization API and database**. The
repository already has a Node/Postgres server and web console, but the tablet
does not currently connect to either. Implement synchronization as a new,
versioned API contract rather than treating a Drive backup file as a shared
database.

Target behavior:

1. A tablet opens and immediately displays its locally cached roster and
   attendance.
2. A background sync checks in with the server and downloads changes since its
   last server cursor.
3. Locally queued changes upload with stable event IDs; the server deduplicates
   retries and returns authoritative revisions.
4. If there is no connection, the tablet remains usable and keeps changes in
   an outbox. Sync retries when connectivity returns and when the app opens.
5. The UI clearly shows last successful sync, pending changes, and errors.

This is eventual consistency: tablets cannot receive each other's changes
while disconnected. If both tablets change data offline, synchronization
reconciles them after either connects.

## Approaches

| Approach | Internet needed? | How it works | Strengths | Limitations |
|---|---|---|---|---|
| Current manual backup/restore | No | Admin transfers a backup file and restores it on another tablet. | Simple, no network configuration, current merge code is reusable. | Manual and delayed; not two-way live sync; concurrent edits are not reconciled as a shared database. |
| Peer-to-peer Wi-Fi / local hotspot | No internet; both devices need the same local network | One tablet or a local coordinator exposes an authenticated sync endpoint; the other connects and exchanges changes. | Works at a site with no internet; potentially quick. | Android local-network discovery, hotspot/client isolation, changing IPs, sleep, and device availability make it operationally fragile. A tablet acting as server must remain awake and reachable. |
| Dedicated local server on the site LAN | No internet after installation | A small PC or appliance runs the sync API/database; tablets sync over the local Wi-Fi. | One authoritative dataset, multi-tablet sync without internet, easier than a tablet acting as host. | Hardware, power, backups, updates, and local network support become the organization's responsibility. Remote access is unavailable unless separately provided. |
| Hosted API and database (recommended) | Yes, for sync | Both tablets use the existing server/API as the authoritative shared state; each keeps a local Room cache and durable outbox. | Reliable multi-device coordination, remote administration, central backups, web-console integration, and easier future growth. | Requires internet, hosting, identity/access control, privacy review, monitoring, and recurring operational cost. Offline edits wait until connectivity returns. |
| Hybrid local + hosted sync | Internet normally; local server can bridge an outage | Tablets sync to a site server and the site server synchronizes with cloud when possible. | Site continues syncing during internet outages and later shares data centrally. | Most complex option: requires server deployment and careful server-to-cloud conflict handling. Consider only after the simple cloud sync is proven. |

Bluetooth or Wi-Fi Direct can transfer a one-time export, but are not a good
primary multi-device database strategy: devices must be nearby, discovery and
reconnection are brittle, and neither provides a stable source of truth.

## Proposed cloud-sync architecture

```text
Tablet A: Compose UI -> ViewModel -> Repository -> Room database
                                      |             |
                                      +-> outbox ---+
                                            |
                                 WorkManager sync worker
                                            |
                               authenticated HTTPS API
                                            |
                           Node API -> PostgreSQL (source of truth)
                                            |
                    Tablet B sync worker -> outbox / Room -> UI

Web console -------------------- same API/database
Google Drive backup ------------ separate recovery snapshot, not sync
```

### Data and conflict contract

- Keep Room as the fast local cache and preserve current offline behavior.
- Add an outbox/change table with a UUID per operation, entity type and ID,
  device ID, operation timestamp, payload/schema version, and sync state.
- Make uploads idempotent on the server using the operation UUID. A retry must
  not create a duplicate attendance event.
- Use server-generated revisions/cursors. Download changes after the tablet's
  last acknowledged cursor, apply them in a transaction, and only then persist
  the new cursor.
- Keep attendance events append-only. Resolve duplicate/reordered events with
  stable event IDs and server ordering; do not silently overwrite an arrival
  or departure using a generic last-write-wins rule.
- Treat member/department changes as versioned records. Member deactivation is
  a tombstone, not a physical delete. Department IDs must be globally stable;
  names are editable labels and must not be used as permanent identifiers.
- Preserve the department snapshot on each attendance event. Renames and member
  department moves must not rewrite history.
- Detect conflicting edits to the same member fields. Return a conflict for
  admin review or define a field-level merge policy; don't silently accept
  whichever tablet happened to sync last.
- Do not synchronize admin PINs or device-local secrets. Establish device
  enrollment and admin authorization separately.
- Face templates require a separate security design: current embeddings are
  encrypted with a device-bound Android Keystore key. Never copy those
  ciphertexts directly between devices. Either retain the existing explicit,
  password-protected portable face transfer, or define a server-mediated
  end-to-end encrypted transfer with explicit consent and key management.

### Connectivity and user experience

- Sync once after local data is ready on app launch, then schedule retry/sync
  work when internet is available. Also sync after a user action that creates
  important changes, with coalescing so rapid edits don't enqueue many jobs.
- Do not block opening Face Scan or Manual Entry on network availability.
- Show `Synced just now`, `Last synced ...`, `N changes waiting`, and a clear
  failure reason in Settings. Do not show a success state until the server has
  acknowledged the uploaded changes.
- Add an admin-only `Sync now` action and a recovery/export route.
- Use TLS, short-lived access tokens, least-privilege device credentials,
  server-side authorization per organization, rate limits, audit records, and
  database backups. Never put service-account credentials in the APK.

## Delivery phases

1. **Contract and threat model:** define organization/device identity, entity
   IDs, event ordering, retention, privacy/consent, and conflict resolution.
2. **Server groundwork:** add versioned sync endpoints, device enrollment,
   idempotent operation ingestion, cursor-based change feed, and API tests.
   The existing Node/Postgres server must be reviewed before exposing it to
   production tablets.
3. **Tablet outbox:** add Room migrations for device ID, outbox, and sync
   cursor. Record local mutations transactionally with their outbox operation.
4. **One-tablet pilot:** implement authenticated WorkManager sync, pull/push,
   retries, and settings status. Verify airplane mode, process death, repeated
   requests, and database upgrades.
5. **Two-tablet concurrency pilot:** exercise simultaneous attendance,
   department rename, member move/deactivation, duplicate roster import, and
   conflicting edits. Verify every event and analytics total against a
   deterministic test oracle.
6. **Operations and rollout:** add server monitoring, database backup/restore,
   support diagnostics, staged deployment, and a documented rollback. Only
   enable multi-device sync after recovery tests pass.

## Acceptance criteria

- Two tablets on one organization converge to the same roster and attendance
  after each reconnects, without duplicate events.
- A tablet with no internet remains fully usable and uploads pending work after
  reconnecting.
- Killing/restarting the app or worker never loses an acknowledged or queued
  operation.
- Rename, member move, deactivation, and concurrent attendance preserve event
  history and report totals.
- Users can see last successful sync and actionable errors; failure is never
  presented as success.
- Face data, PINs, and organization data follow an approved privacy/security
  design before production synchronization is enabled.

## Not included in automatic Google Drive backup today

The scheduled Drive worker uploads the sanitized Room database (roster and
attendance), omitting face templates because their encryption key belongs to
the originating device. A Drive snapshot is for recovery and is not a shared
database. For cross-tablet face transfer, use the separate password-protected
face transfer or full `.sewabackup` workflow and verify enrollment on the
destination tablet.
