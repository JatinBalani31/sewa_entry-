import { Router } from 'express';
import rateLimit from 'express-rate-limit';
import { z } from 'zod';
import { config } from '../config.js';
import { query } from '../db/index.js';
import { authenticate, loadCurrentUser, requireRole, requireKiosk } from '../middleware/auth.js';
import { asyncRoute } from '../middleware/error.js';
import { validate } from '../middleware/validate.js';
import { signKioskToken } from '../lib/tokens.js';
import { verifyKioskPin } from '../services/credentials.js';
import { tap, sewadarDay } from '../services/attendance.js';
import { notFound, badRequest } from '../lib/errors.js';
import { toSewaDate, dayName, isSewaDay } from '../lib/sewaDays.js';

export const kioskRouter = Router();

// A shared tablet is one IP for a whole queue of people, so this ceiling sits
// well above one person's pace while still blocking a scripted PIN sweep.
const kioskLimiter = rateLimit({
  windowMs: 60 * 1000,
  limit: config.security.kioskRequestsPerMinute,
  standardHeaders: 'draft-7',
  legacyHeaders: false,
  message: { error: 'This station is sending too many requests. Wait a minute.' },
});

// ~256 KB of base64 is plenty for a 600x200 signature canvas, and caps what a
// rogue station could store.
const MAX_SIGNATURE_CHARS = 256 * 1024;

// ---------------------------------------------------------------------------
// Provisioning — an admin turns a tablet into a counter station
// ---------------------------------------------------------------------------

const provisionSchema = z.object({
  station_name: z.string().trim().max(60).optional(),
  // A station can be pinned to one department, so the picker shows only that
  // team; left empty it serves everybody.
  department_id: z.coerce.number().int().positive().nullable().optional(),
});

kioskRouter.post(
  '/provision',
  authenticate,
  loadCurrentUser,
  requireRole('ADMIN'),
  validate(provisionSchema),
  asyncRoute(async (req, res) => {
    let department = null;
    if (req.body.department_id) {
      const { rows } = await query(
        'SELECT id, name FROM departments WHERE id = $1 AND is_active = TRUE',
        [req.body.department_id],
      );
      if (!rows[0]) throw notFound(`No active department with ID ${req.body.department_id}.`);
      department = rows[0];
    }

    const stationName = req.body.station_name?.trim()
      || (department ? `${department.name} counter` : 'Sewa counter');

    const token = signKioskToken({
      stationName,
      departmentId: department?.id ?? null,
      departmentName: department?.name ?? null,
      issuedBy: req.user.id,
    });

    console.warn(`[audit] kiosk token issued for "${stationName}" by ${req.user.id}`);
    res.json({ token, station: { name: stationName, department } });
  }),
);

// ---------------------------------------------------------------------------
// Station endpoints — authenticated by the station token, not by a person
// ---------------------------------------------------------------------------

kioskRouter.use(kioskLimiter, authenticate, requireKiosk);

kioskRouter.get('/session', (req, res) => {
  const sewaDate = toSewaDate();
  res.json({
    station: {
      name: req.auth.stationName,
      departmentId: req.auth.departmentId ?? null,
      departmentName: req.auth.departmentName ?? null,
    },
    sewaDate,
    dayName: dayName(sewaDate),
    isSewaDay: isSewaDay(sewaDate),
    issuedBy: req.auth.issuedBy,
    serverTime: new Date().toISOString(),
  });
});

/**
 * The station's Sewadar picker. Names and IDs only — anyone standing at the
 * counter can read this screen, so it carries no phone numbers or emails.
 */
kioskRouter.get(
  '/sewadars',
  asyncRoute(async (req, res) => {
    const search = String(req.query.search || '').trim().toLowerCase();
    const where = ['s.is_active = TRUE'];
    const params = [];

    if (req.auth.departmentId) {
      params.push(req.auth.departmentId);
      where.push(`s.department_id = $${params.length}`);
    }
    if (search) {
      params.push(`%${search}%`);
      where.push(`(LOWER(s.full_name) LIKE $${params.length} OR LOWER(s.id) LIKE $${params.length})`);
    }

    const { rows } = await query(
      `SELECT s.id, s.full_name, d.name AS department_name
         FROM sewadars s
         LEFT JOIN departments d ON d.id = s.department_id
        WHERE ${where.join(' AND ')}
        ORDER BY s.full_name LIMIT 500`,
      params,
    );
    res.json({ sewadars: rows });
  }),
);

/** Lets the station show "you signed in at 09:12" before the PIN is entered. */
kioskRouter.get(
  '/status/:sewadarId',
  asyncRoute(async (req, res) => {
    const { rows } = await query(
      'SELECT id, full_name FROM sewadars WHERE id = $1 AND is_active = TRUE',
      [req.params.sewadarId],
    );
    if (!rows[0]) throw notFound('That Sewadar is not available at this station.');

    const day = await sewadarDay(rows[0].id, toSewaDate());
    res.json({ sewadar: rows[0], ...day });
  }),
);

const punchSchema = z.object({
  sewadar_id: z.string({ required_error: 'Choose your name from the list.' })
    .trim().min(1, 'Choose your name from the list.'),
  pin: z.string({ required_error: 'Enter your PIN.' }).min(1, 'Enter your PIN.'),
  action: z.enum(['IN', 'OUT']).optional(),
  signature: z.string().max(MAX_SIGNATURE_CHARS, 'Signature image is too large.').optional(),
  sewa_date: z.string().regex(/^\d{4}-\d{2}-\d{2}$/, 'Use YYYY-MM-DD.').optional(),
});

kioskRouter.post(
  '/punch',
  validate(punchSchema),
  asyncRoute(async (req, res) => {
    const signature = normaliseSignature(req.body.signature);
    const sewadar = await verifyKioskPin(req.body.sewadar_id, req.body.pin);

    const sewaDate = req.body.sewa_date || toSewaDate();
    const day = await sewadarDay(sewadar.id, sewaDate);
    const action = req.body.action || day.nextAction;

    if (!action) {
      throw badRequest(`${sewadar.full_name} has already signed in and out for ${sewaDate}.`);
    }

    const record = await tap({
      sewadarId: sewadar.id,
      sewaDate,
      action,
      source: 'KIOSK',
      signature,
    });

    res.status(201).json({
      record,
      action,
      sewadar: { id: sewadar.id, full_name: sewadar.full_name },
      message: action === 'IN'
        ? `${sewadar.full_name} signed in.`
        : `${sewadar.full_name} signed out.`,
    });
  }),
);

function normaliseSignature(signature) {
  if (!signature) return null;
  if (!/^data:image\/(png|jpeg);base64,[A-Za-z0-9+/=]+$/.test(signature)) {
    throw badRequest('Signature must be a base64-encoded PNG or JPEG data URL.');
  }
  return signature;
}
