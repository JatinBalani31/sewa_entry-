import { Router } from 'express';
import rateLimit from 'express-rate-limit';
import { z } from 'zod';
import { config } from '../config.js';
import { query } from '../db/index.js';
import { authenticate, loadCurrentUser, requireRole } from '../middleware/auth.js';
import { asyncRoute } from '../middleware/error.js';
import { validate } from '../middleware/validate.js';
import { notFound, badRequest } from '../lib/errors.js';
import {
  tap, selfAdjust, adminSet, listRecords, rosterForDay, sewadarDay,
  getRecordById, deleteRecord, describe,
} from '../services/attendance.js';
import {
  currentOrPreviousSewaDay, recentSewaDays, toSewaDate, isSewaDay, dayName,
} from '../lib/sewaDays.js';

export const attendanceRouter = Router();

const tapLimiter = rateLimit({
  windowMs: 60 * 1000,
  limit: config.security.punchesPerMinute,
  standardHeaders: 'draft-7',
  legacyHeaders: false,
  message: { error: 'Too many requests. Wait a minute and try again.' },
});

attendanceRouter.use(authenticate, loadCurrentUser);

const DATE = z.string().regex(/^\d{4}-\d{2}-\d{2}$/, 'Use YYYY-MM-DD.');
const TIME = z.string().datetime({ message: 'Use an ISO 8601 timestamp.' });

// ---------------------------------------------------------------------------
// A Sewadar's own sewa
// ---------------------------------------------------------------------------

/** What the phone opens to: today's sewa day, and where this Sewadar stands. */
attendanceRouter.get(
  '/me/day',
  asyncRoute(async (req, res) => {
    const date = req.query.date && DATE.safeParse(req.query.date).success
      ? req.query.date
      : toSewaDate();
    res.json(await sewadarDay(req.user.id, date));
  }),
);

/** The last several sewa days, so the phone can show a short history. */
attendanceRouter.get(
  '/me/recent',
  asyncRoute(async (req, res) => {
    const count = Math.min(Number(req.query.count) || 8, 60);
    const dates = recentSewaDays(count);
    const records = await listRecords({ sewadarId: req.user.id, from: dates[0], to: toSewaDate() });
    const byDate = new Map(records.map((r) => [r.sewa_date, r]));

    res.json({
      days: dates.map((date) => ({
        sewaDate: date,
        dayName: dayName(date),
        record: byDate.get(date) || null,
      })).reverse(),
      // Anything recorded off-schedule still belongs in their history.
      extraDays: records.filter((r) => !isSewaDay(r.sewa_date)),
    });
  }),
);

const tapSchema = z.object({
  action: z.enum(['IN', 'OUT'], { required_error: 'Choose IN or OUT.' }),
  sewa_date: DATE.optional(),
});

attendanceRouter.post(
  '/me/tap',
  tapLimiter,
  validate(tapSchema),
  asyncRoute(async (req, res) => {
    const record = await tap({
      sewadarId: req.user.id,
      sewaDate: req.body.sewa_date,
      action: req.body.action,
      source: 'TAP',
    });
    res.status(201).json({
      record,
      message: req.body.action === 'IN'
        ? `Sewa entry recorded at ${new Date(record.in_time).toISOString()}.`
        : `Sewa exit recorded at ${new Date(record.out_time).toISOString()}.`,
    });
  }),
);

const adjustSchema = z.object({
  sewa_date: DATE,
  // null clears a time; omitting the key leaves it untouched.
  in_time: TIME.nullable().optional(),
  out_time: TIME.nullable().optional(),
  notes: z.string().trim().max(500).nullable().optional(),
});

attendanceRouter.patch(
  '/me/day',
  tapLimiter,
  validate(adjustSchema),
  asyncRoute(async (req, res) => {
    const record = await selfAdjust({
      sewadarId: req.user.id,
      sewaDate: req.body.sewa_date,
      inTime: req.body.in_time,
      outTime: req.body.out_time,
      notes: req.body.notes,
    });
    res.json({ record, message: 'Your timings have been updated.' });
  }),
);

// ---------------------------------------------------------------------------
// Admin
// ---------------------------------------------------------------------------

/** The full roster for one sewa day — present and absent alike. */
attendanceRouter.get(
  '/roster',
  requireRole('ADMIN'),
  asyncRoute(async (req, res) => {
    const parsed = DATE.safeParse(req.query.date);
    const date = parsed.success ? parsed.data : currentOrPreviousSewaDay();
    const roster = await rosterForDay(date, req.query.department_id || null);

    res.json({
      sewaDate: date,
      dayName: dayName(date),
      isSewaDay: isSewaDay(date),
      roster,
      present: roster.filter((r) => r.in_time).length,
      stillIn: roster.filter((r) => r.in_time && !r.out_time).length,
      total: roster.length,
      serverTime: new Date().toISOString(),
    });
  }),
);

const listSchema = z.object({
  from: DATE.optional(),
  to: DATE.optional(),
  date: DATE.optional(),
  sewadar_id: z.string().trim().optional(),
  department_id: z.coerce.number().int().positive().optional(),
  limit: z.coerce.number().int().min(1).max(5000).default(1000),
});

attendanceRouter.get(
  '/records',
  requireRole('ADMIN'),
  validate(listSchema, 'query'),
  asyncRoute(async (req, res) => {
    const q = req.validatedQuery;
    const records = await listRecords({
      from: q.from, to: q.to, sewaDate: q.date,
      sewadarId: q.sewadar_id, departmentId: q.department_id, limit: q.limit,
    });
    res.json({ records, count: records.length });
  }),
);

const adminSetSchema = z.object({
  sewadar_id: z.string({ required_error: 'Choose a Sewadar.' }).trim().min(1, 'Choose a Sewadar.'),
  sewa_date: DATE,
  in_time: TIME.nullable().optional(),
  out_time: TIME.nullable().optional(),
  notes: z.string().trim().max(500).nullable().optional(),
});

/** Create or correct any Sewadar's record for a day. */
attendanceRouter.put(
  '/records',
  requireRole('ADMIN'),
  validate(adminSetSchema),
  asyncRoute(async (req, res) => {
    const { rows } = await query('SELECT id, full_name FROM sewadars WHERE id = $1', [req.body.sewadar_id]);
    if (!rows[0]) throw notFound(`No Sewadar with ID ${req.body.sewadar_id}.`);

    const record = await adminSet({
      sewadarId: req.body.sewadar_id,
      sewaDate: req.body.sewa_date,
      inTime: req.body.in_time ?? null,
      outTime: req.body.out_time ?? null,
      notes: req.body.notes,
      adminId: req.user.id,
    });
    res.json({ record, message: `Record saved for ${rows[0].full_name}.` });
  }),
);

const bulkSchema = z.object({
  sewa_date: DATE,
  entries: z.array(z.object({
    sewadar_id: z.string().trim().min(1),
    in_time: TIME.nullable().optional(),
    out_time: TIME.nullable().optional(),
    notes: z.string().trim().max(500).nullable().optional(),
  })).min(1, 'Send at least one entry.').max(500, 'Send at most 500 entries at a time.'),
});

/**
 * Mark a whole department's day in one go — the way an admin actually works
 * through a register. Each entry is applied independently so one bad row does
 * not throw away the rest of the sheet; failures come back per Sewadar.
 */
attendanceRouter.post(
  '/records/bulk',
  requireRole('ADMIN'),
  validate(bulkSchema),
  asyncRoute(async (req, res) => {
    const saved = [];
    const failed = [];

    for (const entry of req.body.entries) {
      try {
        saved.push(await adminSet({
          sewadarId: entry.sewadar_id,
          sewaDate: req.body.sewa_date,
          inTime: entry.in_time ?? null,
          outTime: entry.out_time ?? null,
          notes: entry.notes,
          adminId: req.user.id,
        }));
      } catch (err) {
        failed.push({ sewadar_id: entry.sewadar_id, error: err.message });
      }
    }

    res.json({
      saved: saved.length,
      failed,
      records: saved,
      message: `${saved.length} record(s) saved${failed.length ? `, ${failed.length} failed` : ''}.`,
    });
  }),
);

attendanceRouter.delete(
  '/records/:id',
  requireRole('ADMIN'),
  asyncRoute(async (req, res) => {
    const id = Number(req.params.id);
    const existing = await getRecordById(id);
    if (!existing) throw notFound(`No attendance record with ID ${id}.`);

    const reason = String(req.query.reason || '').trim();
    if (reason.length < 5) throw badRequest('Pass ?reason=... explaining why this record is being removed.');

    await deleteRecord(id);
    console.warn(
      `[audit] attendance ${id} (${existing.sewadar_id} on ${existing.sewa_date}) deleted by ${req.user.id}: ${reason}`,
    );
    res.json({ ok: true, message: `Record for ${existing.sewadar_name} on ${existing.sewa_date} removed.` });
  }),
);

export { describe };
