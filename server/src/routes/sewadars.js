import { Router } from 'express';
import { z } from 'zod';
import { query } from '../db/index.js';
import { authenticate, loadCurrentUser, requireRole } from '../middleware/auth.js';
import { asyncRoute } from '../middleware/error.js';
import { validate } from '../middleware/validate.js';
import { hashSecret, assertValidPin, assertValidPassword } from '../lib/pin.js';
import { badRequest, notFound, conflict } from '../lib/errors.js';
import { publicSewadar } from './auth.js';

export const sewadarsRouter = Router();

const SELECT = `
  SELECT s.id, s.full_name, s.phone, s.email, s.role, s.department_id, s.is_active,
         s.joined_on, s.created_at, s.failed_pin_attempts, s.pin_locked_until,
         (s.password_hash IS NOT NULL) AS has_password,
         d.name AS department_name
    FROM sewadars s
    LEFT JOIN departments d ON d.id = s.department_id`;

sewadarsRouter.use(authenticate, loadCurrentUser, requireRole('ADMIN'));

sewadarsRouter.get(
  '/',
  asyncRoute(async (req, res) => {
    const search = String(req.query.search || '').trim().toLowerCase();
    const departmentId = req.query.department_id;
    const where = [];
    const params = [];

    if (search) {
      params.push(`%${search}%`);
      where.push(`(LOWER(s.full_name) LIKE $${params.length} OR LOWER(s.id) LIKE $${params.length}
                   OR LOWER(s.email) LIKE $${params.length} OR s.phone LIKE $${params.length})`);
    }
    if (departmentId === 'none') {
      where.push('s.department_id IS NULL');
    } else if (departmentId) {
      params.push(Number(departmentId));
      where.push(`s.department_id = $${params.length}`);
    }
    if (req.query.include_inactive !== 'true') where.push('s.is_active = TRUE');

    const clause = where.length ? `WHERE ${where.join(' AND ')}` : '';
    const { rows } = await query(`${SELECT} ${clause} ORDER BY s.full_name`, params);
    res.json({ sewadars: rows.map(decorate) });
  }),
);

sewadarsRouter.get(
  '/:id',
  asyncRoute(async (req, res) => {
    res.json({ sewadar: decorate(await find(req.params.id)) });
  }),
);

const createSchema = z.object({
  id: z.string({ required_error: 'Sewadar ID is required.' })
    .trim().min(1, 'Sewadar ID is required.').max(50)
    .regex(/^[A-Za-z0-9_-]+$/, 'Sewadar ID may use letters, numbers, hyphens and underscores only.'),
  full_name: z.string({ required_error: 'Full name is required.' })
    .trim().min(1, 'Full name is required.').max(100),
  phone: z.string().trim().max(20).nullable().optional(),
  email: z.string().trim().email('Enter a valid email address.').max(100).nullable().optional()
    .or(z.literal('')),
  department_id: z.coerce.number().int().positive().nullable().optional(),
  role: z.enum(['ADMIN', 'SEWADAR']).default('SEWADAR'),
  pin: z.string({ required_error: 'A kiosk PIN is required.' }).min(1, 'A kiosk PIN is required.'),
  // Only meaningful for an admin; it is what opens the console.
  password: z.string().min(1).optional(),
  joined_on: z.string().regex(/^\d{4}-\d{2}-\d{2}$/, 'Use YYYY-MM-DD.').nullable().optional().or(z.literal('')),
  is_active: z.boolean().default(true),
});

sewadarsRouter.post(
  '/',
  validate(createSchema),
  asyncRoute(async (req, res) => {
    const body = req.body;

    let pin;
    let password = null;
    try {
      pin = assertValidPin(body.pin);
      if (body.role === 'ADMIN') {
        if (!body.password) throw new Error('An administrator needs a password to sign in to the console.');
        password = assertValidPassword(body.password);
      } else if (body.password) {
        password = assertValidPassword(body.password);
      }
    } catch (err) {
      throw badRequest(err.message);
    }

    await query(
      `INSERT INTO sewadars (id, full_name, phone, email, department_id, role,
                             secret_pin_hash, password_hash, joined_on, is_active)
       VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10)`,
      [
        body.id.trim(), body.full_name, body.phone || null, body.email || null,
        body.department_id ?? null, body.role, await hashSecret(pin),
        password ? await hashSecret(password) : null,
        body.joined_on || null, body.is_active,
      ],
    );

    res.status(201).json({ sewadar: decorate(await find(body.id.trim())) });
  }),
);

const updateSchema = z.object({
  full_name: z.string().trim().min(1).max(100).optional(),
  phone: z.string().trim().max(20).nullable().optional(),
  email: z.string().trim().email('Enter a valid email address.').max(100).nullable().optional()
    .or(z.literal('')),
  department_id: z.coerce.number().int().positive().nullable().optional(),
  role: z.enum(['ADMIN', 'SEWADAR']).optional(),
  joined_on: z.string().regex(/^\d{4}-\d{2}-\d{2}$/, 'Use YYYY-MM-DD.').nullable().optional().or(z.literal('')),
  is_active: z.boolean().optional(),
});

sewadarsRouter.patch(
  '/:id',
  validate(updateSchema),
  asyncRoute(async (req, res) => {
    const target = await find(req.params.id);

    // Losing the last administrator would lock everyone out of the console.
    const losingAdmin = target.role === 'ADMIN'
      && (req.body.is_active === false || (req.body.role && req.body.role !== 'ADMIN'));
    if (losingAdmin) await assertNotLastAdmin(target.id);

    if (req.body.role === 'ADMIN' && target.role !== 'ADMIN' && !target.has_password) {
      throw badRequest('Set a password for this Sewadar before making them an administrator.');
    }

    const fields = [];
    const params = [target.id];
    for (const [key, value] of Object.entries(req.body)) {
      params.push(value === '' ? null : value);
      fields.push(`${key} = $${params.length}`);
    }
    if (!fields.length) throw badRequest('No fields to update.');

    await query(`UPDATE sewadars SET ${fields.join(', ')} WHERE id = $1`, params);
    res.json({ sewadar: decorate(await find(target.id)) });
  }),
);

const setPinSchema = z.object({
  pin: z.string({ required_error: 'A new PIN is required.' }).min(1, 'A new PIN is required.'),
});

sewadarsRouter.post(
  '/:id/set-pin',
  validate(setPinSchema),
  asyncRoute(async (req, res) => {
    const target = await find(req.params.id);
    let pin;
    try {
      pin = assertValidPin(req.body.pin);
    } catch (err) {
      throw badRequest(err.message);
    }
    await query(
      'UPDATE sewadars SET secret_pin_hash = $2, failed_pin_attempts = 0, pin_locked_until = NULL WHERE id = $1',
      [target.id, await hashSecret(pin)],
    );
    res.json({ ok: true, message: `Kiosk PIN set for ${target.full_name}.` });
  }),
);

const setPasswordSchema = z.object({
  password: z.string({ required_error: 'A password is required.' }).min(1, 'A password is required.'),
});

sewadarsRouter.post(
  '/:id/set-password',
  validate(setPasswordSchema),
  asyncRoute(async (req, res) => {
    const target = await find(req.params.id);
    let password;
    try {
      password = assertValidPassword(req.body.password);
    } catch (err) {
      throw badRequest(err.message);
    }
    await query(
      'UPDATE sewadars SET password_hash = $2, failed_pin_attempts = 0, pin_locked_until = NULL WHERE id = $1',
      [target.id, await hashSecret(password)],
    );
    res.json({ ok: true, message: `Console password set for ${target.full_name}.` });
  }),
);

sewadarsRouter.post(
  '/:id/unlock',
  asyncRoute(async (req, res) => {
    const target = await find(req.params.id);
    await query(
      'UPDATE sewadars SET failed_pin_attempts = 0, pin_locked_until = NULL WHERE id = $1',
      [target.id],
    );
    res.json({ ok: true, message: `${target.full_name} can sign in again.` });
  }),
);

sewadarsRouter.delete(
  '/:id',
  asyncRoute(async (req, res) => {
    const target = await find(req.params.id);
    if (target.id === req.user.id) throw conflict('You cannot remove your own account.');
    if (target.role === 'ADMIN') await assertNotLastAdmin(target.id);

    // Deleting would cascade their whole attendance history away, so anyone
    // who has ever done sewa is deactivated instead.
    const { rows } = await query(
      'SELECT COUNT(*)::int AS n FROM attendance WHERE sewadar_id = $1',
      [target.id],
    );
    if (rows[0].n > 0) {
      await query('UPDATE sewadars SET is_active = FALSE WHERE id = $1', [target.id]);
      return res.json({
        ok: true,
        deactivated: true,
        message: `${target.full_name} has ${rows[0].n} attendance record(s) and was made inactive rather than deleted.`,
      });
    }

    await query('DELETE FROM sewadars WHERE id = $1', [target.id]);
    res.json({ ok: true, deleted: true, message: `${target.full_name} was removed.` });
  }),
);

async function find(id) {
  const { rows } = await query(`${SELECT} WHERE s.id = $1`, [String(id).trim()]);
  if (!rows[0]) throw notFound(`No Sewadar with ID ${id}.`);
  return rows[0];
}

async function assertNotLastAdmin(excludingId) {
  const { rows } = await query(
    `SELECT COUNT(*)::int AS n FROM sewadars
      WHERE role = 'ADMIN' AND is_active = TRUE AND id <> $1`,
    [excludingId],
  );
  if (rows[0].n === 0) {
    throw conflict('This is the last active administrator — appoint another one first.');
  }
}

function decorate(row) {
  const lockedUntil = row.pin_locked_until ? new Date(row.pin_locked_until) : null;
  return {
    ...publicSewadar(row),
    has_password: Boolean(row.has_password),
    failed_pin_attempts: row.failed_pin_attempts ?? 0,
    is_locked: Boolean(lockedUntil && lockedUntil > new Date()),
    pin_locked_until: lockedUntil ? lockedUntil.toISOString() : null,
  };
}
