# Sewa Entry — running the system

Attendance for an NGO's Sewadars, organised by department, recorded on the
weekly sewa days, and exported department-wise to Excel.

[Readme.md](Readme.md) is the original specification. This document describes
what was actually built and how to run it.

---

## What this is

One application, built for phones and tablets first — the admin console
included, because it is used on a tablet as often as on a laptop.

| Surface | Route | Who signs in | What they do |
|---|---|---|---|
| **Sewadar** | `/` | Sewadar ID / phone + **PIN** | Sign in and out of sewa, correct their own timings |
| **Counter tablet** | `/kiosk` | Nobody — the tablet itself is the credential | Sewadars sign in with their ID and PIN |
| **Admin console** | `/admin/*` | Admin ID / email + **password** | Departments, Sewadars, the register, reports |

```
   Sewadar's phone        Counter tablet         Admin on a tablet
   (tap in / tap out)     (ID + PIN)             (register, reports)
          │                     │                        │
          └─────────────────────┼────────────────────────┘
                                │  HTTPS + JWT
                    ┌───────────▼────────────┐
                    │   Express API :4001    │
                    │  auth · attendance ·   │
                    │  departments · excel   │
                    └───────────┬────────────┘
                                │
                        ┌───────▼────────┐
                        │   PostgreSQL   │
                        └────────────────┘
```

### The sewa week

Sewa runs on **Wednesday and Sunday** by default (`SEWA_DAYS=0,3`). Attendance
on any other day is still accepted — it is recorded and flagged as *extra
sewa* rather than refused, because a volunteer roster should not turn people
away for turning up.

### Timings are flexible on purpose

A Sewadar taps **Sign IN** when they arrive and **Sign OUT** when they leave.
If they forget, they can set the time themselves, up to
`TIME_ADJUST_TOLERANCE_MINUTES` (default **2 hours**) either side of the moment
they actually tapped. A day they never tapped at all can be filled in from
scratch, up to `BACKFILL_DAYS` (default **14 days**) back.

Every record keeps both numbers: the time on the register, and the untouched
moment the button was pressed. So a report can always show what was adjusted
and by how much, without anybody having to be strict about it in the moment.

An administrator correcting the register is not bound by either limit.

---

## Quick start

### Option A — no database yet (fastest look)

The server boots against an in-process PostgreSQL emulation and loads demo data
automatically. Nothing is persisted; every restart starts clean.

```bash
npm run setup
```

```bash
npm run build && npm start
```

Open <http://localhost:4001> and sign in as `ADM001` / `sewa@admin1`.

### Option B — real PostgreSQL via Docker

```bash
cp .env.example .env
```

Set `JWT_SECRET` in `.env` to a long random string, then:

```bash
npm run db:up
```

```bash
npm run setup && npm run migrate && npm run seed && npm run build && npm start
```

### Option C — everything in Docker

```bash
docker compose up -d --build
```

### Development, with hot reload

```bash
npm run dev
```

The console runs on <http://localhost:5173> and proxies `/api` to the API on
`:4001`. In production the API serves the built console itself, so the whole
system is one port.

---

## Demo data

`npm run seed` creates eight departments — Accounts, BAV, Traffic, Luggage,
Mobile, Safai, Transport and Langar — each with an HOD, 22 Sewadars spread
across them, and the last eight Wednesdays and Sundays of attendance. That
history deliberately includes the awkward cases the reports exist to catch: a
few people who forgot to sign out, a record whose times were nudged afterwards,
and one entry on an unscheduled day.

| Who | Signs in with | Credential |
|---|---|---|
| Priya Nair (admin) | `ADM001` or `admin@sewa.local` | password `sewa@admin1` |
| Rakesh Iyer (admin) | `ADM002` | password `sewa@admin2` |
| Sewadars | `SW1001`, `SW2001`, … or their phone number | a random 4-digit PIN, printed by the seed |

**Change all of these before real use.** Run `npm run seed` without `--quiet`
to see the generated PINs, or set your own from **Sewadars → PIN**.

### Two credentials, two jobs

- A **PIN** is short and numeric because it gets typed on the counter tablet's
  keypad by whoever is next in the queue.
- A **password** belongs to administrators and is what opens the console.

Once an account has a password, the PIN can no longer sign in to the console —
it still works at the counter. That way a four-digit code is never a route into
the admin screens.

Anyone can change their own credential from the button in the top bar. An admin
sets someone else's from **Sewadars → PIN**, which also clears any lockout.

---

## Day-to-day use

### A Sewadar, on their phone
Open the app, tap **Sign IN** on arrival and **Sign OUT** on leaving. If they
forgot, **Correct my timings** lets them fix it. **History** shows their last
40 sewa days with hours worked.

### At the counter
An admin pairs the tablet once (**Counter → Pair this device**), optionally
pinning it to a single department so the picker shows only that team. A Sewadar
then taps their name, types their PIN, and optionally signs on screen.

If the Wi-Fi drops, entries are held on the tablet and pushed automatically
when it returns; the counter shows how many are waiting.

### An admin, marking a department
**Register** is the working screen: pick a sewa day and a department, and every
Sewadar in it appears with In and Out time fields. **Fill unmarked** stamps a
standard shift onto everyone not yet marked, so a whole team is one tap plus
corrections. Only the rows that actually changed are saved.

---

## The Excel export

`GET /api/reports/excel` streams a workbook built around departments:

1. **Summary** — one row per department: head-count, attendances, turnout
   percentage, total and average hours, and how many people never signed out.
2. **Register** — the classic attendance sheet. Sewadars down the side, sewa
   days across the top, `09:13–16:46` in each cell, colour-coded: green for a
   complete day, amber for a missing sign-out, grey for absent.
3. **All Entries** — the flat list, one row per Sewadar per sewa day.
4. **One sheet per department**, headed with the department name and its HOD.
5. **Exceptions** — missing sign-outs, implausibly long days, times that were
   adjusted by an hour or more, and anything recorded off-schedule.
6. **Report Info** — the period, filters, timezone and row counts, so a saved
   file explains itself months later.

Every sheet has a frozen header and an auto-filter.

---

## API

All endpoints are under `/api`. Everything except `/health`, `/time` and
`/auth/login` needs `Authorization: Bearer <token>`.

### Auth
| Method | Path | Notes |
|---|---|---|
| `POST` | `/auth/login` | `{ identifier, secret }` — ID, email or phone, plus PIN or password |
| `GET` | `/auth/me` | Current user, server time, and the sewa schedule |
| `POST` | `/auth/change-secret` | Changes whichever credential this account signs in with |
| `POST` | `/auth/change-pin` | Changes the counter PIN specifically |

### Attendance
| Method | Path | Role | Notes |
|---|---|---|---|
| `GET` | `/attendance/me/day` | any | Today's record and what to do next |
| `GET` | `/attendance/me/recent` | any | The last N sewa days |
| `POST` | `/attendance/me/tap` | any | `{ action: 'IN' \| 'OUT' }` |
| `PATCH` | `/attendance/me/day` | any | Set own times, within the tolerance |
| `GET` | `/attendance/roster` | admin | Everyone for a day, present and absent |
| `GET` | `/attendance/records` | admin | Filter by date range, Sewadar, department |
| `PUT` | `/attendance/records` | admin | Create or correct one record |
| `POST` | `/attendance/records/bulk` | admin | Save a whole department's day |
| `DELETE` | `/attendance/records/:id?reason=…` | admin | Reason required, written to the audit log |

### Departments and Sewadars
| Method | Path | Role |
|---|---|---|
| `GET` | `/departments`, `/departments/:id` | any signed-in user |
| `POST PATCH DELETE` | `/departments`, `/departments/:id` | admin |
| `GET POST PATCH DELETE` | `/sewadars`, `/sewadars/:id` | admin |
| `POST` | `/sewadars/:id/set-pin`, `/set-password`, `/unlock` | admin |

### Counter and reports
| Method | Path | Auth |
|---|---|---|
| `POST` | `/kiosk/provision` | admin — returns a station token |
| `GET` | `/kiosk/session`, `/kiosk/sewadars`, `/kiosk/status/:id` | station |
| `POST` | `/kiosk/punch` | station |
| `GET` | `/reports/summary`, `/reports/excel` | admin |

---

## How the rules are enforced

- **One record per Sewadar per sewa day**, held by a database constraint. A day
  has one arrival and one departure, so "who has not signed out" is a plain
  `NULL` check rather than a pairing exercise.
- **Times come from the server clock** when someone taps. Adjustments are
  explicit, bounded, and recorded alongside the original.
- **PINs and passwords are bcrypt hashes** and are never returned by any
  endpoint.
- **Three wrong attempts freeze an account for five minutes.** The counter
  lives in the database, so the cooldown survives a restart and holds across
  API instances. Per-IP rate limits sit in front of it.
- **A wrong secret and an unknown account return the same message**, and both
  spend the same time in a bcrypt comparison, so the endpoint cannot be used to
  work out who is on the roster.
- **Counter tokens and personal tokens are not interchangeable.** A station
  token cannot reach `/sewadars`; a person's token cannot reach `/kiosk/punch`.
- **The counter's Sewadar list carries names and IDs only** — no phone numbers,
  no emails — because anyone standing at the counter can read that screen.
- **Removing a Sewadar who has sewa history deactivates them instead**, so the
  records survive. The same applies to a department with members.
- **A department's name is stamped onto each attendance record**, so moving
  someone between teams does not silently rewrite past reports.

---

## Testing

```bash
npm test
```

73 tests, run against the in-memory database so no Docker is needed:

- `tests/sewaDays.test.js` — the sewa calendar (which days count, what "the
  current sewa day" means on a Tuesday, ranges across month boundaries) and the
  PIN and password policies.
- `tests/api.test.js` — auth for both credential types, role separation,
  department and Sewadar management, tapping, self-adjustment inside and
  outside the tolerance, backfill limits, the roster, bulk saves, counter
  punches, and a real `.xlsx` whose sheets are opened and checked.
- `tests/offlineQueue.test.js` — the counter's offline queue: ordering, what is
  retried, what is dropped, and that corrupt storage cannot wedge it.
- `tests/ratelimit.test.js` — the per-IP login limiter.

---

## Installing on a phone, and surviving a dropped network

The console ships a web manifest and icons, so Android Chrome offers *Add to
home screen* and the app then opens without browser chrome. On iOS it is
*Share → Add to Home Screen*. Installation needs a secure origin.

A service worker caches the app shell so a counter tablet that is reloaded on
dropped Wi-Fi comes back up instead of showing a browser error page. Nothing
under `/api/` is ever cached — a cached record would misrepresent what the
server holds.

> Verification note: the service worker's script serves correctly and its
> registration is wrapped so a failure only logs a warning and leaves the app
> working online. Registration itself could not be exercised here — the preview
> browser used for testing refuses to register any service worker. Confirm it in
> a real browser via DevTools → Application → Service Workers.

---

## Where this differs from the original specification

The original [Readme.md](Readme.md) described a GPS-geofenced employee
attendance system. The scope was then changed to an NGO Sewadar roster, and the
following followed from that:

- **No GPS or geofencing.** Removed at your direction, along with the sites
  table, the map, and the mock-location guard. Sewadars are identified, not
  located.
- **Sewadars, not employees**, throughout the interface, the database and the
  reports.
- **Departments replace sites** as the organising dimension, since the export
  is department-wise.
- **Attendance is a day record, not an event stream.** The original schema
  logged individual IN/OUT rows; a twice-weekly roster with one arrival and one
  departure per day is better served by one row per Sewadar per day, and it is
  what makes the register sheet fall out of the query directly.
- **HODs are recorded but hold no permissions**, as you asked. Each department
  names its head; the field is ready for a future role check without any schema
  change.
- **Administrators sign in with a password**, not a PIN.

---

## Before going live

- [ ] Set a real `JWT_SECRET` (`openssl rand -base64 48`). The server refuses to
      start in production without one.
- [ ] Point `DATABASE_URL` at a real PostgreSQL instance and take backups. On a
      real, empty database the app creates exactly one administrator on first
      boot (see `BOOTSTRAP_ADMIN_*` in `.env.example`) — change that password
      immediately, then add your real departments and Sewadars from the
      console. Don't run `npm run seed` against it; that's demo data for
      trying the app out locally.
- [ ] Terminate TLS in front of the API.
- [ ] Confirm `SEWA_DAYS` and `REPORT_TIMEZONE` match the sangat's actual
      schedule and timezone.
- [ ] Decide whether `TIME_ADJUST_TOLERANCE_MINUTES` (2 hours) and
      `BACKFILL_DAYS` (14) suit how strict you want to be.
- [ ] Remove the `POSTGRES_PORT` mapping from `docker-compose.yml` so the
      database is not reachable from outside the container network.

**Deploying for free, with GitHub Actions doing the pushing?** See
[DEPLOY.md](DEPLOY.md) for the full one-time setup (Render + Neon, no card
required) and the CI/CD pipeline.
