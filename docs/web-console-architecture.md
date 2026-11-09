# Web Console Architecture and Delivery Plan

## Purpose

Evolve the existing React web console and Node/Postgres API into the future
master operations portal for the Sewa Entry tablet. The portal should support
administrator management, roster ownership, useful attendance analytics, and
configurable forms with collected inputs and secretary signatures.

This is a forward-looking plan, not a declaration that tablet synchronization
or form workflows are already implemented. Requirements for individual forms,
their fields, approval steps, and signature placement can be added later
without requiring a rewrite of the attendance and roster foundations.

## Current system

```text
Android tablet (Kotlin / Room)              Web console (React / Vite)
  Face Scan, Manual Entry, Analytics           Admin Dashboard, Register,
  Roster, Enrollment, Settings                 Sewadars, Departments, Reports
               |                                             |
               | local database; offline                    | HTTPS / JSON
               |                                             v
               |                                  Node / Express API
               |                                             |
               |                                             v
               |                                      PostgreSQL
               |
               +----- No tablet-to-server sync yet --------+
```

- The tablet is currently the local attendance record and must keep working
  without internet. Keep tablet Analytics until the web portal is live and
  synchronization is deployed and verified on the device.
- The web application already has administrator Dashboard, Register,
  Sewadars, Departments, Reports, and Excel export screens. It is a useful
  base for the master portal, not a new application to replace.
- The API already provides authenticated departments, roster, attendance,
  kiosk, and report endpoints.
- The demo seed now contains the 40 populated department names from the
  selected `All Sewadar Mobile No.xlsx` workbook. Seed members and attendance
  are synthetic demonstration data, not the real roster or production data.
- The real workbook contains contact information and remains outside source
  control. Production members and attendance must not be copied into demo seed
  code or committed files.

## Target architecture

```text
Browser: React console
  Admin shell and role-aware navigation
  Roster/master data | Register and corrections | Analytics and exports
  Form template builder | Submissions and review | Signature management
                         |
                         | versioned HTTPS API, JWT, audit context
                         v
Node / Express API
  Authentication and authorization
  Roster import/merge | Attendance and device sync | Report queries
  Form schema/version service | Submission workflow | Document renderer
                         |
                         +---------------- PostgreSQL
                         |                    |
                         |                    +-- relational operational data
                         |                    +-- immutable audit history
                         |
                         +---------------- private object storage
                                              roster imports, signature
                                              source files and generated PDFs

Android tablet
  Offline-first Room database and current attendance UX
  Durable outbox -> idempotent sync -> server acknowledgement
  Pull master roster/configuration changes with explicit conflict handling
```

The existing single API service can continue to host these modules initially.
The boundaries above are logical modules, not a requirement to split into
microservices. Store large uploads and generated documents in private object
storage; store metadata, ownership, hashes, workflow state, and audit events
in PostgreSQL.

## Web portal domains

### 1. Identity and administration

- Keep the existing administrator login and JWT/session model.
- Evolve the current `ADMIN` / `SEWADAR` model to explicit, server-enforced
  capabilities when multi-admin operations are introduced.
- Suggested capability groups: roster management, attendance correction,
  report access, form design, form review/signing, and administrator/security
  management.
- Secretary and Area Secretary responsibilities should be represented as
  assignable permissions or workflow roles; do not infer authority from a
  person's name, department, or uploaded signature.
- Record actor, time, operation, and affected record for privileged changes.

### 2. Roster and department master

- Treat the portal's department and sewadar records as the future master data
  after tablet synchronization is live.
- Preserve the tablet-compatible fields: department name/status/order; member
  name, department, primary and secondary contacts, optional organization,
  HOD marker, and active status.
- Provide preview-first Excel import: inspect sheets and headers, identify
  duplicates and ambiguous department/member matches, show a proposed change
  summary, then require an explicit apply action.
- Match conservatively: department by normalized name; member by normalized
  name within a department, with phone as a review hint rather than an
  unconditional identity key.
- Blank imported cells should not erase saved details. Never delete attendance
  history as a side effect of roster import or member removal.
- Retain the source filename, import time, actor, and a count/summary of
  additions, updates, skipped rows, and conflicts. Keep original files private
  and apply a retention policy before storing them long term.

### 3. Attendance register and analytics

- Preserve one daily summary row per member and sewa date for compatibility
  with the existing register and reports. Keep immutable/idempotent attendance
  events as the synchronization and audit unit.
- The dashboard should filter by date and department, show present/absent,
  still-in (missing departure), turnout, and recorded hours, and include
  departments with zero attendance.
- Reports should support date range, department, member, exception review, and
  Excel export. Clearly distinguish absent, incomplete/open, manually adjusted,
  and complete days.
- Align calculations with the tablet's attendance rules and test the same
  boundary cases against both products before relying on portal totals.
- Label synthetic demo records in development and demonstration environments.
  Do not present them as actual historical attendance.
- Provide an admin correction flow with required reason/note, actor, original
  values, and resulting values retained in the audit history.

### 4. Dynamic forms and signed documents

Build this as a separate domain so evolving form requirements do not distort
attendance records.

- Store form templates as versioned schemas with stable field IDs, labels,
  field types, required/optional status, ordering, and validation rules.
- Start with a deliberately small field vocabulary: short/long text, number,
  date, single/multi-select, checkbox, and file upload. Add conditional rules,
  repeatable groups, and calculated fields only when specific forms require
  them.
- Save each submission against the exact template version used to render it;
  later template edits must not change previously submitted answers.
- Keep submitted answers as structured, validated JSON associated with
  relational submission metadata. Keep uploaded documents outside the database
  in private storage and reference them by opaque object key.
- Model workflow state independently of the template: draft, submitted,
  awaiting signatures/review, completed, rejected/returned, and archived are
  candidate states to confirm with stakeholders.
- A signature asset is not an authorization credential. Check the user's
  authenticated role at signing time and keep an audit record of the signer,
  asset version, timestamp, and document version.
- Preserve original signature uploads. Accept JPG/PNG as image assets; convert
  validated PDF signature sources to a controlled image representation only
  after defining page selection, crop, transparency, and placement rules.
- Generate the final PDF from the immutable submitted answers and a recorded
  signature-placement configuration. Keep the final PDF, its checksum, and
  generation metadata. Do not describe a stamped image as a cryptographic
  digital signature.
- Leave template ownership, submitter permissions, signer order, approval
  sequence, rework behavior, signature placement, and final output layout
  configurable decisions until the detailed form requirements are supplied.

## Tablet synchronization and master ownership

Synchronization is a prerequisite to calling the portal authoritative or
removing tablet Analytics.

1. Assign stable server identities to tablets and generate stable client event
   IDs on the device.
2. Add a Room-backed outbox. Write the attendance event and outbox item in the
   same local transaction; do not require a live connection to save attendance.
3. Upload batches with idempotency keys. The server must safely acknowledge
   retries without duplicate attendance events.
4. Persist server acknowledgements and retry transient failures with bounded
   backoff. Surface permanent validation or identity conflicts to an admin;
   never silently discard pending events.
5. Pull roster/configuration changes with version markers. Do not overwrite
   local edits or attendance with a stale snapshot.
6. Define conflict policy per entity before launch: immutable attendance events
   append; daily summaries are derived/reconciled from events; roster edits
   require field-level precedence or explicit review; deactivation is not
   deletion.
7. Add device health/sync status, last successful sync, pending count, failure
   detail, and a supported export/recovery route.
8. Test duplicate uploads, interrupted batches, clock skew, concurrent
   corrections, device replacement, restore/import, and extended offline
   operation.
9. Run a pilot with tablet Analytics and portal reports side by side. Remove
   tablet Analytics only after stakeholders approve parity, connectivity is
   reliable, recovery is demonstrated, and pending-event loss is prevented.

## API and data evolution

- Keep REST/JSON and the existing Express routes as the initial interface.
- Add explicit API versioning for breaking contracts and document request,
  response, error, and idempotency shapes.
- Add database migrations for new entities; avoid destructive schema changes.
- Keep operational entities normalized: departments, sewadars, devices,
  attendance events/daily summaries, form templates/versions, submissions,
  signature assets, and audit events.
- Store timestamps in UTC and render using the configured sangat timezone;
  store sewa dates as calendar dates.
- For reports, keep queries bounded by date/department and add indexes based
  on measured query plans. Large exports should stream or run as a tracked
  export job rather than exhausting request memory.
- Apply authorization at the API boundary for every object and operation.
  Validate uploads by size and detected content, use private storage, and
  avoid exposing sensitive contact data in kiosk or public responses.

## Phased delivery and acceptance

### Phase A — current MVP readiness

- Seed all 40 current department names and clearly synthetic demonstration
  members/attendance.
- Ensure dashboard and reports include zero-attendance departments.
- Walk through department, roster, register, exception, and Excel flows with
  admin users.
- Keep tablet Analytics and document the lack of tablet/server sync.

**Exit:** web build and API tests pass; demo seed is repeatable; report totals
and department lists are consistent and labelled.

### Phase B — real roster onboarding

- Build and test preview-first roster import/merge with a copied,
  access-controlled workbook.
- Pilot it in a non-production environment, reconcile department/member counts,
  and obtain owner approval before any production import.
- Add an import audit and a rollback/recovery plan.

**Exit:** owners approve the import summary and conflict handling; no demo
contacts are mixed into production.

### Phase C — connected master operations

- Implement the tablet outbox/server sync contract, admin correction audit,
  and sync-health UI.
- Run offline/retry/conflict/recovery testing, followed by a monitored pilot.

**Exit:** no unacknowledged event is lost under the agreed failure tests;
tablet and portal totals reconcile; stakeholders approve the portal as master.

### Phase D — configurable forms and signed output

- Confirm initial form templates, field rules, roles, signing sequence,
  signature file requirements, placement, final document format, and retention.
- Implement versioned schemas, structured submissions, access control,
  signature asset management, PDF generation, and audit trail.
- Pilot with non-sensitive sample data and test rejected/returned and amended
  submissions before release.

**Exit:** each initial form has an approved template and workflow; completed
documents reproduce exactly from the recorded submission/template/signature
versions.

## Open decisions for later requirements

- Who may create/publish templates, submit forms, review, reject, and sign?
- What are the initial form types and exact fields, validation, conditional
  rules, and repeatable sections?
- Is signature order fixed or form-specific? Are both signatures mandatory?
- Are uploads JPG, PNG, or PDF? For PDF, which page and crop rule identify the
  signature? Should a transparent PNG master be requested?
- Where do signatures appear, and is the output always PDF?
- How long are drafts, source documents, signature assets, and completed
  records retained?
- Which records may be edited after signing, and how should corrected versions
  be identified?
- What connectivity, hosting, backup, recovery-time, and data-residency
  requirements apply to the live portal?
