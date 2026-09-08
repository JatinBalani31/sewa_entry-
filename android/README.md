# Sewa Entry — Tablet Register

The Android app that replaces the paper register on the sewa counter. It runs
entirely on the tablet, needs no network, and is the admin console: whoever
holds the tablet can record, correct and report on attendance. The roster is
imported directly from the sangat's existing contact workbook, and the admin
tabs are protected by a PIN.

### What it replaces

Today the sangat does this in three manual passes:

1. **Paper sheets** at the counter — one per department per sewa day
2. Someone **types those into Excel** afterwards
3. Someone **prepares the quarterly summary** from that spreadsheet

This app does all three at once. Recording an arrival *is* the spreadsheet
row, and the quarterly summary is already computed by the time the last person
signs out — no re-typing, no separate summarising step.

The system is not redesigned, only digitalised: same departments, same sewa
days, same register, same quarterly summary at the end.

---

## Where this sits

The repository holds three pieces. Only the first is in use right now.

| Piece | Path | State |
|---|---|---|
| **Tablet register** (this app) | `android/` | The live product. Native Kotlin, local database, offline. |
| Node/Postgres API | `server/` | Built and tested. Intended for the later phase where sewadars sign in from their own phones. |
| React web console | `web/` | Built and tested. The browser-based admin console that pairs with the API. |

The tablet deliberately does **not** talk to the server yet. Its schema mirrors
the server's (departments → sewadars → one attendance row per sewadar per sewa
day) so the two can be reconciled when phone sign-in is added, but today the
tablet is self-contained and authoritative.

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

64 unit tests covering the sewa calendar, the hours arithmetic, quarter
boundaries, department subtotals, the spreadsheet writer, the roster import
parser, and the admin PIN validation. They run on the JVM — no device needed.

---

## Using it

The app has three tabs: **Register**, **Summary** and **Roster**. The Register
tab opens without any PIN — the tablet sits on the counter and the queue must
not wait. The Summary and Roster tabs are behind a **4-digit admin PIN**. On
first use the admin tabs are open (there is nothing to protect yet); once a
PIN is set, the same PIN unlocks both tabs for the session. A "Lock admin"
button in the header re-locks them.

The app opens in **portrait** by default and follows the system auto-rotate
setting, so it switches to landscape when the tablet is turned sideways.

### First-time setup (admin)

A fresh install starts with no departments, no sewadars, and no PIN. Follow
these steps to make the tablet ready for the sewa counter:

1. **Install the APK.**
   Transfer `app-debug.apk` to the tablet and open it, or use ADB:
   ```
   adb install app-debug.apk
   ```

2. **Go to the Roster tab.**
   On a fresh tablet with no PIN configured, all three tabs are accessible.
   Tap the **Roster** tab.

3. **Import the sewadar roster.**
   Tap **Import from Excel** and pick the sangat's contact workbook
   ("List of HOD & All Sewadar Contact Detail"). The app reads every sheet
   as a department, detects HODs from bold names, and shows a preview. Check
   the departments, then tap **Import N sewadars**.

4. **Set the admin PIN.**
   After the import, an amber banner at the top of the Roster tab says
   "No admin PIN set." Tap **Set PIN**, choose a 4-digit PIN, and confirm it.
   From now on, the Summary and Roster tabs are locked behind that PIN.

5. **Back up to Google Drive.**
   Still on the Roster tab, tap **Backup**. The Android file picker opens —
   choose **Google Drive** (or any location). The database file is saved. Do
   this regularly so no recorded sewa is ever lost.

6. **Place the tablet on the counter.**
   Tap **Lock admin** in the top bar. The Register tab is now the only one
   accessible without the PIN. The tablet is ready for the queue.

**To restore on a new tablet:** Install the APK, go to the Roster tab, tap
**Restore**, pick the backup file from Google Drive, and confirm. The app
restarts with all departments, sewadars, and attendance records from the
backup.

### The register

1. **Pick the day.** The bar at the top steps a day at a time, or tap it for a
   month calendar where every Wednesday and Sunday is ringed. A dot marks days
   that already have records.
2. **Pick the department.** Departments come from the roster import (see
   [The roster](#the-roster)); add more individually as needed.
3. **Tap a name.** The action bar appears bottom-right.
4. **Arrival**, then **Departure** — each takes a signature and fills its chip.

Tiles carry their own state per department *and* per day, so switching
department flips them to that team's records for the selected date.

### Today vs any other day

| | Today | A past day |
|---|---|---|
| Arrival / Departure buttons | Shown | **Hidden** |
| Manual entry | Available | The only way in |

The live buttons stamp the device clock. On a past day that clock would record
the wrong thing, so the buttons are withheld entirely rather than offered and
then corrected. The header shows a **"Past day — manual entry only"** marker so
it is obvious why.

### Manual entry

The grey **Manual** button sits beside Arrival and Departure, the same size, and
is always available — including on today, for corrections.

The dialog is built for speed on a tablet:

- **HH : MM**, two two-digit boxes, pre-filled with a sensible default
  (the recorded time, or 09:30 / 16:30 for a fresh entry)
- Typing two digits in the hour box **jumps to minutes automatically** — a full
  time is four keystrokes
- Large **+ / −** steppers above and below each box, so a time can be set
  without the keyboard at all; hours wrap 23 → 00 and minutes 59 → 00
- Out-of-range input is clamped rather than rejected, so the field can never
  hold something unsavable

Any time set this way is **flagged as edited**. The register shows it as
`09:30*` and the export carries an "Entered by hand" column. A departure can
never be saved before its arrival, and a departure with no arrival is refused.

Nothing is ever locked. An admin can revisit any day, any department, any
sewadar, and change or clear what is recorded.

---

## The quarterly summary

The **Summary** tab opens on the current quarter, because producing the
quarterly summary is the job it exists for. Four compact dropdowns along the
top control what is shown:

- **Period** — eight rolling quarters plus "This month", "Last 6 months",
  "Last 1 year" and "All time"
- **Sewa days** — All / Wednesdays only / Sundays only
- **Group by** — Overall / Weekly / Monthly / Quarterly
- **Department** — All or narrow to one team

The summary is organised department by department with a subtotal under each
and a grand total at the bottom, which is how the hand-prepared one reads.

Any grouping other than Overall shows a matrix instead: sewadars down the side,
periods across the top, scrolling horizontally because a year of weeks is wider
than any tablet.

**Export Excel** writes what is on screen to
`Downloads/sewa_hours_<range>_<filters>.xlsx`:

| Sheet | Contents |
|---|---|
| **Summary \<quarter\>** | **The deliverable.** Department blocks, a line per sewadar, the quarter's three months as columns, a subtotal per department and a grand total |
| Hours by Sewadar | Flat per-person totals: days attended, complete, open, total and average hours, last sewa |
| *Period* hours / detail | Sewadars × periods matrix and its long-form twin *(only when grouping is not Overall)* |
| Register | Every underlying row — this is the sheet that used to be typed up by hand |
| Report Info | The exact quarter and filters used, totals, and how hours are counted |

### How hours are counted

A day counts as **attended** if it has an arrival. It counts as **complete**
only if it also has a departure, and **only complete days contribute hours**.

A day someone signed in for and never signed out of shows as an *open day* — in
the register, in the analytics table, and in the export. This is deliberate: a
missing sign-out is a fact to be chased, not a number to be guessed at, and
silently inventing an end time would corrupt the very total the system exists
to produce.

---

## The roster

The **Roster** tab imports the sangat's existing "List of HOD & All Sewadar
Contact Detail" workbook — one sheet per department, names down the side, HODs
in bold.

**Import from Excel** opens a file picker. The app reads the `.xlsx` directly
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
updates phone numbers and HOD status in place rather than creating duplicates.
No sewadar is ever deleted, so recorded sewa can never be lost.

The parser handles the real workbook's structural quirks: side-by-side tables
on one sheet, blank rows between table sections, trailing spaces in names,
scratch-copy sheets with duplicate department names, and sheets with no bold
name (imported with no HOD marked).

A new tablet starts empty — there is no seed data. The Roster tab is the first
step: import the contact sheet and every department arrives named exactly as the
sangat names it.

---

## How it is built

```
android/app/src/main/java/org/sewaentry/tablet/
├── MainActivity.kt          Three-tab shell: Register | Summary | Roster
├── data/
│   ├── Entities.kt          Room tables: departments, sewadars, attendance
│   ├── Daos.kt              Queries, including the register's LEFT JOIN
│   ├── SewaDatabase.kt      Database with migration v1→v2
│   ├── SewaRepository.kt    Attendance writes and roster import
│   ├── AdminLock.kt         Salted SHA-256 PIN storage
│   ├── SewaDays.kt          The sewa calendar (Wed/Sun) and time formatting
│   └── Reporting.kt         Pure hours arithmetic — filters, buckets, totals
├── export/
│   ├── XlsxWriter.kt        Dependency-free .xlsx writer
│   └── AttendanceExport.kt  Builds the workbook and saves to Downloads
├── roster/
│   ├── XlsxReader.kt        Dependency-free .xlsx reader (SAX on ZIP)
│   └── RosterImport.kt      Workbook → ImportPlan parser
└── ui/
    ├── RegisterScreen.kt    Tiles, action bar, arrival/departure flow
    ├── RegisterViewModel.kt All screen state and report computation
    ├── ManualTimeDialog.kt  The HH:MM stepper
    ├── SignatureDialog.kt   Finger-drawn signature capture
    ├── SewaCalendar.kt      Date bar and month picker
    ├── StatsScreen.kt       Dropdown filters, totals table, period matrix
    ├── RosterScreen.kt      File picker, import preview, conflict UI
    ├── AdminGate.kt         Custom numeric keypad for PIN entry
    ├── Dialogs.kt           Add sewadar
    └── Theme.kt             Colours, shared with the web console
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

**The admin PIN is salted SHA-256 in SharedPreferences.** `AdminLock` stores a
random salt alongside the hash and uses constant-time comparison
(`MessageDigest.isEqual`). The PIN protects the Summary and Roster tabs; the
Register tab stays open so the queue is never blocked.

**Import matches by name, never deletes.** When re-importing an updated
workbook, `applyImport` matches sewadars by name within each department. New
names are added, existing names have their phone numbers and HOD status
updated. Names that disappear from the sheet can optionally be deactivated
(hidden from the register) but are never deleted, so attendance history is
preserved.

---

## What to build next

### Replacing the signature

The signature exists to prove *this person was here*. It is the weakest link:
slow on a busy day, and a scrawl proves little. Options, roughly in order of
how well they fit:

| Option | Fit | Notes |
|---|---|---|
| **Per-sewadar PIN** | Strong | 4 digits on a keypad. Fast, works on any tablet, and the server already models a `secret_pin_hash` — so the phone client and the tablet would share one credential. Main cost: people forget PINs, so an admin reset flow is needed. |
| **Fingerprint** | Good, with a caveat | Android's `BiometricPrompt` only verifies *the device owner*, not an arbitrary person — so it cannot tell one sewadar from another on a shared tablet. Doing this properly needs a **separate USB/Bluetooth fingerprint reader** with its own enrolment database. Real hardware cost, and biometric data carries real handling obligations. |
| **NFC / RFID card** | Very good for a queue | A tap is the fastest possible check-in and cards cost little. Needs an NFC-capable tablet and a card per sewadar. Cards get lent, so pair with a photo on screen. |
| **QR code on a sewadar card** | Good, cheapest | Print a QR per sewadar; the tablet camera scans it. No extra hardware beyond a printer, and a lost card is trivially reissued. |
| **Photo capture** | Supplementary | A front-camera snapshot at sign-in is strong evidence and needs no hardware, but is storage-heavy and raises consent questions. Better as an option on exceptions than as the default. |

A practical path: **PIN as the default**, since it costs nothing and lines up
with the credential the server already has; **QR cards** for departments with
long queues; keep the signature available as the fallback for anyone who
forgets everything.

### Other things worth adding

- **Sync to the server**, so the tablet's records and the phone sign-ins become
  one dataset. The schemas already line up; what is missing is a sync worker and
  a conflict rule (last-write-wins per field is probably enough at this scale).
- **A "who is still in" view** at the end of a sewa day, so open days get closed
  before everyone leaves rather than being chased a week later.
- **Backup/restore of the local database** to a file, so a lost or broken
  tablet does not take the register with it. Worth doing *before* the sync work,
  because it is much simpler and covers the same disaster.
- **Bulk mark**, for a department where nearly everyone arrived at the same
  time — set one time, tick the people it applies to.
- **Sewadar photos** on the tiles, which makes picking the right name from a
  grid of similar names much faster.
