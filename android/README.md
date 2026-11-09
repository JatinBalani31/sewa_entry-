# Sewa Entry — Tablet Register

The Android app that replaces the paper register on the sewa counter. Attendance
and face recognition run locally, and the core register works without internet.
Internet is optional for Google Drive database backups. Admins can record and
correct attendance locally; the browser-based web console is a separate
reporting and administration surface. The roster can be imported from the
sangat's existing contact workbook, and tablet admin screens are protected by
a PIN.

### What it replaces

Today the sangat does this in three manual passes:

1. **Paper sheets** at the counter — one per department per sewa day
2. Someone **types those into Excel** afterwards
3. Someone **prepares the quarterly summary** from that spreadsheet

This app records arrivals and departures directly. Reports and analytics are
available in the web console.

The tablet keeps the same departments, sewa days, and register while offline.
The tablet and web console are not synchronized yet; Drive backups are
recovery snapshots, not synchronization.

---

## Where this sits

The repository holds three pieces. Only the first is in use right now.

| Piece | Path | State |
|---|---|---|
| **Tablet register** (this app) | `android/` | The live product. Native Kotlin, local database, offline. |
| Node/Postgres API | `server/` | Built and tested. Provides the data service for the web console. |
| React web console | `web/` | Built and tested. The browser-based master administration and reporting surface. |

The tablet deliberately does **not** talk to the application server. It is
single-device and offline-first, with optional internet use for scheduled Drive
backups. It has no automatic cross-device or web-console sync, and its Room
database is authoritative for that tablet. The schema mirrors the server's
(departments → sewadars → attendance) for future compatibility.

---

## Running it

### Prerequisites
- Android Studio (bundles a JDK 17+), or a standalone JDK 17+ and the Android SDK
- SDK platform **android-34**, build-tools 34+
- A tablet or emulator on **Android 8.0 (API 26)** or newer

### From Android Studio
Open the `android/` folder as a project and press Run. Android Studio writes
`local.properties` itself.

### From the command line

```bash
cd android
```

Create `local.properties` pointing at your SDK. Use **forward slashes** — a
`.properties` file treats `\` as an escape character, so Windows paths written
with backslashes get mangled:

```properties
sdk.dir=C:/Users/<you>/AppData/Local/Android/Sdk
```

Then:

```bash
./gradlew :app:assembleDebug
```

The APK lands at `app/build/outputs/apk/debug/app-debug.apk`.

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Tests

```bash
./gradlew :app:testDebugUnitTest
```

Unit tests cover the sewa calendar, hours arithmetic, quarter boundaries,
department subtotals, spreadsheet writing, roster import, admin PIN validation,
face-template encryption/integrity, and the enrollment sample-count policy.
They run on the JVM — no device needed.

---

## Using it

The tablet provides **Face Scan**, **Manual Entry**, **Analytics**, **Face
Enrollment**, and **Settings**. Roster and data-transfer controls are reached
from the bottom of Settings, not the general menu, to reduce accidental edits.
Face Scan is the kiosk/default screen; admin screens require the **6-digit
admin PIN**. Tablet analytics are computed from the local database and remain
available offline. They are separate from the web console until synchronization
is implemented. A fresh install
requires the admin to choose and confirm a PIN before any tab opens. On the
first launch after upgrading an older install, the admin must enter the old
4-digit PIN and replace it with a new 6-digit PIN. New PINs are bcrypt-hashed
in Android Keystore-backed encrypted preferences. Three failed attempts cause
a five-minute cooldown; repeated lockouts last longer. The admin session locks
after 60 seconds without activity, or when **Lock admin** is tapped. App
updates installed over the existing app keep its private database; Room applies
the explicit schema migrations without a destructive reset. Do not uninstall
the app or clear its storage when updating.

Use **Settings → Change admin PIN** to enter the current PIN, then the new PIN
twice. Pressing Back from an admin screen returns to Face Scan; Back exits only
when Face Scan is already the top screen.

The app follows the device's orientation setting (`fullUser`): use landscape
on the tablet for the intended counter layout, and portrait is supported on
phones. Compose layouts adapt to the available width and height: the face scan
places camera and results side by side on wide landscape screens and stacks
them on narrow or portrait screens; face enrollment switches between a
scrollable instruction pane and a stacked phone layout. Manual register cards
use an adaptive grid and scroll when the roster exceeds the visible area.
Interface measurements use density-independent units, so 1080p and 4K displays
scale with Android's configured display density rather than requiring a
resolution-specific layout.

---

## Google Drive automatic database backup

Google sign-in is used only to authorize a Drive backup account; it does not
replace the tablet's Admin PIN. Use a dedicated Google account owned by the
organization, enable two-step verification, restrict who can sign in to it,
and keep its recovery method current. The selected interval is scheduled with
Android WorkManager and a connected-network constraint. If the tablet is
offline, Android runs the backup after connectivity returns. Daily, weekly,
and monthly intervals are approximate: Android battery optimization and
network availability can delay a run. A one-time backup is also queued after
connecting an account or changing an enabled schedule.

### Configure Google OAuth for a build

Google OAuth credentials are project-specific and cannot be supplied by this
repository. Before the account connection button can work:

1. In Google Cloud Console, create/select the organization's project, enable
   the Google Drive API, and configure the OAuth consent screen.
2. Create an **Android OAuth client** for application ID
   `org.sewaentry.tablet`, with the SHA-1 signing fingerprints used for the
   debug and release builds.
3. Create a **Web application OAuth client** for the ID-token request. If the
   consent screen is in Testing, add the dedicated backup account as a test
   user.
4. Put the Web client ID in the developer's user-level Gradle properties file
   (not in a committed source file):

   ```properties
   GOOGLE_SERVER_CLIENT_ID=your-web-client-id.apps.googleusercontent.com
   ```

5. Rebuild and install the app. In **Settings → Google Drive backup**, connect
   the dedicated account, choose Daily/Weekly/Monthly, and verify that the
   status changes to a successful backup and shows a recent successful time.

The client ID is an OAuth identifier, not a private key, but keep project
configuration out of shared source when practical. Never put a service-account
private key or OAuth client secret in the Android APK. A public release may
require Google OAuth verification depending on the consent-screen audience and
requested scopes.

The scheduled backup uploads a SQLite snapshot of **roster and attendance** to
the connected account's Drive. Face templates are omitted because their
encryption key is bound to the source device. The snapshot is protected by the
Google account's Drive access controls and Google-managed storage encryption;
the app does not apply the user-selected `.sewabackup` password to this
automatic Drive file. Restrict access to the dedicated account. For a portable,
password-protected transfer including face templates, create a local full
`.sewabackup` and use the explicit restore flow. Drive backup is not a restore
or synchronization feature in the app.

The Settings status reports queued/running/success/failure and the last
successful time. A transient upload/network error is retried; permanent Google
authorization and file errors are shown with a suggested action. Check Settings
after a scheduled run to confirm it succeeded. If Google OAuth is not configured
for the build, Settings explains that the developer must configure the Web
client ID first.

### First-time setup (admin)

A fresh install starts with no departments, no sewadars, and no PIN. Follow
these steps to make the tablet ready for the sewa counter:

1. **Install the APK.**
   Transfer `app-debug.apk` to the tablet and open it, or use ADB:
   ```
   adb install app-debug.apk
   ```

2. **Set the admin PIN.**
   On first launch, choose and confirm a six-digit PIN on the lock screen.
   The same PIN unlocks Manual Entry, Roster, Face Enrollment, and Settings.

3. **Import the sewadar roster.**
   Open **Settings → Roster and data transfer**, tap **Import Excel**, and
   pick the sangat's contact workbook
   ("List of HOD & All Sewadar Contact Detail"). The app reads every sheet
   as a department, detects HODs from bold names, and shows a preview. Check
   the departments, then tap **Import N sewadars**. HOD is stored as ordinary
   organization text rather than a separate member flag.

4. **Create a local full backup.**
   In **Settings → Roster and data transfer**, choose **Backup**. Choose a password
   of at least 10
   characters, then save the `.sewabackup` file to device storage, USB, or
   Google Drive. The encrypted file includes roster, attendance, and face
   entries. Keep the password somewhere separate from the file and back up
   regularly. The password encrypts the backup and is not written into the file.

5. **Place the tablet on the counter.**
   Tap **Lock admin** in the top bar. Manual Entry is locked until the PIN is
   entered again.

**To import data from another tablet or recover a backup:** Install the APK,
choose a new six-digit PIN if needed, then open **Settings → Roster and data
transfer** and tap **Restore**. Choose the `.sewabackup` file and enter its
password. The import
merges departments and members, appends attendance events, keeps the newer
attendance row for a member and day, and re-encrypts face samples with this
tablet's Keystore key. Local records absent from the file are preserved. The
full backup records its source timezone, so imported attendance times retain
their original displayed local time on the destination tablet. Older
`.sewabackup` and SQLite `.db` files have no timezone metadata and are imported
without time adjustment.
admin PIN is device-specific and is not included in the backup. No sewadar-level
PINs are stored or transferred. Older SQLite `.db` backups can also be imported,
but those files do not include face entries.
Android cloud/device backup excludes the live database because its face
encryption key is device-bound; use this full backup for recovery.

Restore is merge-only; the app has no full-replacement restore option because
it could accidentally erase the tablet's roster and attendance. Use a separate
tablet if you need to inspect another backup without merging it.

The **Roster and data transfer** section in Settings is also where admins can
import or export the Excel roster and transfer encrypted face data. Excel
exports include a header row. The roster option is not in the general menu to
reduce accidental changes.

### Manual Entry

1. **Unlock Manual Entry** with the six-digit admin PIN. It locks again after
   60 seconds without activity; the tab stays selected and asks for the admin
   PIN again when it locks.
2. **Pick the day.** The bar at the top steps a day at a time, or tap it for a
   month calendar where every Wednesday and Sunday is ringed. A dot marks days
   that already have records.
3. **Pick the department.** Departments come from roster import (see
   [Roster and data transfer](#roster-and-data-transfer)). Rename a department
   in Settings; there are no department add/delete controls.
4. **Tap a name.** The action bar appears bottom-right.
5. **Arrival**, then **Departure** — Departure remains visible but disabled
   until Arrival has been recorded. **Manual** time entry asks for the tablet's
   six-digit Admin PIN before showing the time controls. Clearing a saved
   time asks for confirmation before removing it.

Tiles carry their own state per department *and* per day, so switching
department flips them to that team's records for the selected date.

### Face Scan

Face Scan works offline on Wednesdays and Sundays when active roster members
have enrolled templates. **Settings → Face scan mode** selects direct detection
or the default liveness detection. Liveness mode requires a center, side turn,
and return to center; direct mode skips that challenge. Both modes match the
on-device embedding against the encrypted gallery. Two consecutive hits must
identify the same person at the biometric kiosk's 0.62 cosine threshold. The
recognized name is shown before an event is saved. By default, the scan pauses
for the counter operator to confirm or cancel the detected person when enabled;
the setting defaults to direct recording, and **Settings → Face attendance
confirmation** can turn confirmation on. The first
face scan of the day records **IN**. Every later face scan records **OUT**,
updating the daily departure time while preserving the original arrival; each
scan remains in the
local attendance log. An unlocked admin can arm a one-time direction override
with an admin ID and mandatory note. Manual Entry remains available behind the
admin PIN and keeps its own IN/OUT controls.

Attendance is stored only on this tablet; nothing is sent to a server or another
device. The `attendance` table keeps its legacy daily summary, while
`attendance_logs` preserves every IN/OUT transition from both face scans and
manual attendance actions for possible future device import and
interval-aware analytics. Live manual actions alternate IN/OUT; manual time
corrections remain edits to the summary and its corresponding log.
Head-turn detection is a basic liveness check, not a spoof-proof system; a
well-prepared replay may still pass.

Member management is available from the Manual Entry action bar: select a
member to use **Edit** (immediately left of **Remove**). Editing updates the
name, contact, optional organization, and department. Department changes apply
to future entries; existing attendance keeps the department recorded when it
was entered. **Remove** requires two
confirmations and the Admin PIN; it deactivates the member while preserving saved
attendance and face data.

### Face Enrollment

Enrollment is admin-only. Obtain the volunteer's consent before tapping Enroll;
tapping it opens the camera immediately. Each enrollment captures five
samples. The camera and detector process frames in memory; the app does not
save or send face images. It stores only AES-GCM-encrypted 192-value face
embeddings, the `mobilefacenet_112_v1` model version and consent metadata. The
encryption key is held in Android Keystore. The full local `.sewabackup` file
contains face entries in password-protected portable form and re-encrypts them
with the destination tablet's key during import. Face data can be deleted per
person from the Face Enrollment screen. To move enrollments between tablets, use
**Face data import / export** in **Settings → Roster and data transfer** after importing the roster on
the destination tablet. The roster `.xlsx` does not contain face enrollments.
Face export creates an encrypted `.sewaface` file; choose a password of at
least 10 characters and enter the same password during import. The face file
contains embeddings and consent/model metadata, not camera images. The importer
re-encrypts templates with the destination tablet's Android Keystore key and
reports imported/verified samples, preserved newer local enrollments, unmatched
members and ambiguous member matches. Contact numbers help resolve changed or
duplicate names. Review the import result and confirm each expected member shows
enrolled samples in Face Enrollment before scanning.

Alternatively, a password-protected full local `.sewabackup` includes the
roster, attendance and enrolled face templates together. Restore merges the
data into the destination tablet and re-encrypts imported face templates with
that tablet's key. Google Drive database backups omit face templates.

Enrollment and Face Scan reuse the existing biometric kiosk's float32
MobileFaceNet model and eye-alignment contract so both sides produce compatible
embeddings. The model is not quantized. Google Drive scheduled database backups omit face templates; use the password-protected full local backup or face-transfer file to preserve them across tablets.


### Today vs any other day

| | Today | A past day |
|---|---|---|
| Arrival / Departure buttons | Shown | **Hidden** |
| Manual entry | Available | The only way in |

The Arrival and Departure buttons stamp the device clock. On a past day that clock would record
the wrong thing, so the buttons are withheld entirely rather than offered and
then corrected. The header shows a **"Past day — manual entry only"** marker so
it is obvious why.

### Manual entry

The grey **Manual** button sits beside Arrival and Departure, the same size.
Manual time changes and clearing an entry require the six-digit Admin PIN. The
dialog asks for confirmation before clearing only the Arrival or Departure
currently selected, and records the change in the audit log. Admin ID and
override-note fields apply only to a one-time face-scan direction override.

The dialog is built for speed on a tablet:

- **HH : MM**, two two-digit boxes, pre-filled with a sensible default
  (the recorded time, or 07:00 / 14:00 for a fresh entry)
- Typing two digits in the hour box **jumps to minutes automatically** — a full
  time is four keystrokes
- Large **+ / −** steppers above and below each box, so a time can be set
  without the keyboard at all; minute steppers move to the next or previous
  five-minute mark, and hours wrap 23 → 00
- Out-of-range input is clamped rather than rejected, so the field can never
  hold something unsavable

Any time set this way is **flagged as edited**. The register shows it as
`09:30*` and the export carries an "Entered by hand" column. A departure can
never be saved before its arrival, and a departure with no arrival is refused.

An admin can revisit any day, any department, and any sewadar while the
six-digit admin session is open.

---

## Reports and analytics

The tablet Analytics screen remains the local reporting surface while the
device is offline. The web console's **Dashboard** and **Reports** are being
prepared for future master operations; until connectivity and synchronization
are live, they do not automatically include tablet attendance. Tablet
attendance remains local.

---

## Roster and data transfer

The admin-only **Roster and data transfer** screen is opened from the bottom of
Settings; it is intentionally absent from the general menu. It imports the
sangat's existing "List of HOD & All Sewadar
Contact Detail" workbook — one sheet per department, names down the side, HODs
in bold.

**Import Excel** opens a file picker. The app reads the `.xlsx` directly
(no Apache POI — a hand-rolled SAX parser on the ZIP of XML parts) and shows a
preview before anything is saved:

- A stat bar: departments, sewadars, HODs found, sheets skipped
- A per-sheet checklist with checkboxes, so the admin can include or exclude
  individual departments
- Conflict warnings when two sheets name the same department (the canonical
  sheet is included by default; the duplicate starts excluded)
- An "Archive anyone no longer on the sheet" checkbox — ticking it deactivates
  sewadars whose names no longer appear, without deleting them or their
  attendance history

Re-importing an updated workbook matches by name within each department: it
updates phone numbers and organization in place rather than creating
duplicates. Bold HOD names are stored as ordinary organization text. **Export
Excel (with header)** writes the active roster to Excel, including both contact
numbers and organization, without a separate HOD column. Department lists are alphabetical;
rename a department in **Settings** to preserve its ID and attendance history.
No sewadar is ever deleted, so recorded sewa can never be lost.

The parser handles the real workbook's structural quirks: side-by-side tables
on one sheet, blank rows between table sections, trailing spaces in names,
scratch-copy sheets with duplicate department names, and sheets with no bold
name (imported without the HOD marker in the organization text).

A new tablet starts empty — there is no seed data. Open **Settings → Roster
and data transfer** to import the contact sheet; every department arrives
named exactly as the sangat names it.

---

## How it is built

### Runtime architecture

```text
Compose screens
    ↓ user actions and StateFlow UI state
RegisterViewModel / FaceEnrollmentViewModel / FaceScanViewModel
    ↓
SewaRepository and feature-specific repositories
    ↓ Room DAOs + SQLite (local source of truth)

CameraX → ML Kit face detection → face alignment → TensorFlow Lite embedding
                                      ↓
                              encrypted face templates

XLSX document picker → SAX/ZIP workbook reader → import preview → transaction
Room rows → XLSX writer → document picker

WorkManager + internet constraint → Google OAuth account → Drive v3 API
              scheduled recovery snapshot; no cross-device sync
Web console -------------------- separate client; no tablet synchronization
```

### Main data and feature responsibilities

- **Navigation and access:** `MainActivity.kt` owns Face Scan/Manual Entry
  tabs, the admin menu, Settings, time-limited admin sessions, and local app
  preferences. `AdminLock.kt` stores/verifies the device's six-digit Admin PIN
  with encrypted preferences. PINs are not transferred to other tablets.
- **Database:** `Entities.kt` defines departments, sewadars, daily attendance,
  append-only attendance logs, override audit rows, and face templates.
  `SewaDatabase.kt` configures Room and explicit migrations (current database
  version 9); migrations preserve roster, attendance history, and face rows.
  `Daos.kt` contains queries and projections. Room is the source of truth for
  the current tablet.
- **Business rules:** `SewaRepository.kt` validates attendance transitions,
  writes the daily summary and event log transactionally, and imports roster
  changes without physically deleting members. `Reporting.kt` and
  `SewaDays.kt` contain pure calendar/hour/reporting rules.
- **Manual attendance:** `RegisterScreen.kt` and its ViewModel display
  department/member state. Live Arrival/Departure actions use the device time;
  edited time entry verifies the Admin PIN, validates arrival/departure order,
  and audits a cleared entry.
- **Face attendance:** CameraX supplies frames; ML Kit detects faces;
  alignment and the TFLite model produce embeddings. Matching and the
  center/side/center liveness policy are implemented under `face/`. Templates
  are AES-GCM encrypted with an Android Keystore key. Face photos are not
  persisted. Face liveness reduces risk but is not spoof-proof.
- **Roster spreadsheets:** `roster/XlsxReader.kt` parses `.xlsx` parts using
  ZIP/XML streaming; `RosterImport.kt` converts sheets to an `ImportPlan`.
  The admin reviews a scrollable preview before applying changes.
  `export/RosterExport.kt` and `XlsxWriter.kt` export a header row and active
  members. Bold HOD metadata is stored as ordinary organization text.
- **Backup and recovery:** `FullLocalBackup.kt` creates a password-protected
  `.sewabackup`; restore is merge-only and can carry portable face templates.
  `GoogleDriveBackup.kt` schedules roster/attendance snapshots with WorkManager
  and Drive OAuth; it omits face templates. Neither mechanism synchronizes
  live changes between tablets.
- **Analytics:** attendance rows retain a department ID snapshot. Moving a
  member affects future entries; historical entries retain the recorded
  department. Renaming a department keeps its ID, so historical reports show
  the current name for that department.

### Google Drive build configuration

`app/build.gradle.kts` reads `GOOGLE_SERVER_CLIENT_ID` as a Gradle property.
Leave credentials out of the repository. Configure Android and Web OAuth
clients, Drive API access, app package/SHA-1 fingerprints, and consent
screen/test users as described in [Google Drive automatic database
backup](#google-drive-automatic-database-backup). Without the Web client ID,
Google account connection is disabled and Settings identifies the missing
configuration.

### Multi-device roadmap

The recommended cloud and offline/LAN alternatives, conflict rules, sync
architecture, rollout phases, and acceptance criteria are documented in
[MULTI_DEVICE_SYNC_PLAN.md](./MULTI_DEVICE_SYNC_PLAN.md). Do not use the Drive
backup file as a shared writable database.

```
android/app/src/main/java/org/sewaentry/tablet/
├── MainActivity.kt          Navigation, admin session, settings and backup flow
├── data/
│   ├── Entities.kt / Daos.kt / SewaDatabase.kt  Room schema, queries, migrations
│   ├── SewaRepository.kt    Attendance, roster edits and import transactions
│   ├── AdminLock.kt         Admin PIN verification and lockout policy
│   ├── BackupManager.kt     Database snapshot creation
│   ├── FullLocalBackup.kt   Password-protected portable backup and restore
│   ├── FaceTemplateTransfer.kt  Portable face-template transfer
│   ├── GoogleDriveBackup.kt WorkManager schedule and Drive upload
│   ├── SewaDays.kt          Sewa calendar and time formatting
│   └── Reporting.kt         Hours arithmetic, filters and totals
├── face/                    Face alignment, embedding, matching and liveness
├── export/
│   ├── XlsxWriter.kt        Dependency-free .xlsx writer
│   ├── RosterExport.kt      Headered roster workbook
│   └── AttendanceExport.kt  Attendance report workbook
├── roster/
│   ├── XlsxReader.kt        .xlsx reader (SAX on ZIP)
│   └── RosterImport.kt      Workbook → ImportPlan parser
└── ui/
    ├── RegisterScreen.kt    Attendance register and action bar
    ├── RegisterViewModel.kt Register state and report computation
    ├── ManualTimeDialog.kt  PIN-gated time correction and clear confirmation
    ├── FaceScanScreen.kt    Camera scan, match confirmation and direction UI
    ├── FaceEnrollmentScreen.kt  Enroll/delete/transfer face templates
    ├── SettingsScreen.kt    Admin preferences, departments and backup status
    ├── RosterScreen.kt      Import preview, export and data transfer
    └── StatsScreen.kt       Local analytics and reports
```

### Decisions worth knowing

**One attendance row per sewadar per sewa day**, enforced by a unique index. A
sewa day has one arrival and one departure, so the register's shape and the
table's shape are the same thing — "who has not signed out" is a plain `NULL`
check rather than a pairing exercise over an event stream.

**Departments are snapshotted onto each attendance row.** Moving someone
between teams later does not rewrite what past reports say.

**Reports are computed in Kotlin, not SQL.** The filtering and bucketing is the
part most likely to be wrong, so it lives in `Reporting.kt` as pure functions —
shared by the screen and the export, and unit-tested directly.

**The spreadsheet writer and reader are both hand-rolled.** An `.xlsx` is a ZIP
of XML parts, so writing *and reading* one costs a few hundred lines each and no
dependency. Apache POI is the usual answer on the JVM but is a poor fit on
Android: large, drags in `java.awt` classes the platform lacks, and pushes
against the dex method limit. The reader uses SAX parsing and resolves bold
formatting through the xlsx styles chain (cellXfs → fonts → `<b/>`), because
bold marks the HOD in the contact workbook.

**Signatures are PNGs, base64 in the row.** Stored with the attendance record,
so the register is self-evidencing without a separate file store.

**The admin PIN is bcrypt-hashed in encrypted preferences.** Existing legacy
PINs are verified and migrated on upgrade. Admin pages require the six-digit
PIN; Face Scan remains the home screen.

**Import matches by name, never deletes.** When re-importing an updated
workbook, `applyImport` matches sewadars by name within each department. New
names are added, existing names have their contact numbers, optional
organization, and HOD organization text updated. Names that disappear from the
sheet can optionally be deactivated (hidden from the register) but are never
deleted, so attendance history is preserved.

---

## What to build next

### Replacing the signature

The signature exists to prove *this person was here*. It is the weakest link:
slow on a busy day, and a scrawl proves little. Options, roughly in order of
how well they fit:

| Option | Fit | Notes |
|---|---|---|
| **Fingerprint** | Good, with a caveat | Android's `BiometricPrompt` only verifies *the device owner*, not an arbitrary person — so it cannot tell one sewadar from another on a shared tablet. Doing this properly needs a **separate USB/Bluetooth fingerprint reader** with its own enrolment database. Real hardware cost, and biometric data carries real handling obligations. |
| **NFC / RFID card** | Very good for a queue | A tap is the fastest possible check-in and cards cost little. Needs an NFC-capable tablet and a card per sewadar. Cards get lent, so pair with a photo on screen. |
| **QR code on a sewadar card** | Good, cheapest | Print a QR per sewadar; the tablet camera scans it. No extra hardware beyond a printer, and a lost card is trivially reissued. |
| **Photo capture** | Supplementary | A front-camera snapshot at sign-in is strong evidence and needs no hardware, but is storage-heavy and raises consent questions. Better as an option on exceptions than as the default. |

A practical path: **QR cards** for departments with long queues; keep the
signature available as the fallback for anyone who forgets their card. Manual
time corrections remain protected by the tablet's Admin PIN.

### Other things worth adding

- **Sync to the server**, so the tablet's records and the phone sign-ins become
  one dataset. The schemas already line up; what is missing is a sync worker and
  a conflict rule (last-write-wins per field is probably enough at this scale).
- **A "who is still in" view** at the end of a sewa day, so open days get closed
  before everyone leaves rather than being chased a week later.
- **Tablet-to-console synchronization** with an explicit conflict and recovery
  contract, so local attendance and the web console can safely become one
  authoritative dataset.
- **Bulk mark**, for a department where nearly everyone arrived at the same
  time — set one time, tick the people it applies to.
- **Sewadar photos** on the tiles, which makes picking the right name from a
  grid of similar names much faster.

---

## Face Detection Incident & Attendance Correction SOP

In the event of a face detection or recognition error (e.g., poor lighting, extreme angle, accessories, liveness prompt timeout, camera malfunction, or un-enrolled sewadar):

### 1. Immediate Reporting by Sewadar
- If a sewadar cannot be recognized by the kiosk scanner or encounters an error message (e.g., *"Face not recognized"*, *"Please wait at least 5 minutes between face scans"*, or camera status errors):
  1. The sewadar must immediately report to the counter administrator, duty sewadar, or their department Head of Department (HOD).
  2. The sewadar provides their **Full Name**, **Department**, and the actual **Arrival** or **Departure** time.

### 2. Manual Attendance Correction Workflow
- The counter administrator unlocks the **Manual Entry** tab on the tablet using the **6-digit Admin PIN**.
- Select the sewadar's **Department** from the dropdown.
- Tap the sewadar's tile in the grid:
  - **Live Entry**: Tap **Arrival** or **Departure** on the action bar to immediately log attendance at the current device time.
  - **Adjust Time / Past Time**: Tap **Manual** to open the time dialog:
    - Default Arrival Time: **7:00 AM**
    - Default Departure Time: **2:00 PM**
    - Adjust hours and minutes if needed, then tap **Save time**.
- **Audit transparency**: Manually adjusted times are marked with `*` on the
  register and exports. Time corrections are protected by the Admin PIN and
  recorded in the local attendance history; clearing a time requires a second
  confirmation.

### 3. Face Enrollment Troubleshooting
- If a sewadar repeatedly encounters recognition failures:
  1. Open **Face enrollment** from the top-right menu (protected by Admin PIN).
  2. Search for the sewadar's name.
  3. If templates exist, tap **Delete face data** to clear stale/incompatible embeddings.
  4. Tap the sewadar to re-enroll with adequate ambient lighting, neutral facial expression, and proper head rotation.
